(ns nos.ops.docker
  (:require
   [nos.core :as nos]
   [failjure.core :as f]
   [clojure.string :as str]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [contajners.core :as c]
   [taoensso.timbre :as log]
   [clj-compress.core :refer [create-archive]])
  (:import [java.io BufferedReader]
           [java.nio ByteBuffer CharBuffer]
           [java.nio.charset Charset StandardCharsets]
           [java.util Arrays]
           [org.apache.commons.compress.archivers.tar TarArchiveOutputStream]
           [org.apache.commons.io FilenameUtils]
           [org.apache.commons.compress.utils IOUtils]))

(def api-version "v4.0.0")

(defn get-info
  "Get the podman system info.
  Useful for checking connection. The `conn` is of format `{:uri
  \"http://localhost:8080\"}`"
  [conn]
  (c/invoke
   (c/client {:engine :podman
              :category :libpod/info
              :conn conn
              :version api-version})
   {:op :SystemInfoLibpod}))

(defn- relativise-path [base path]
  (let [f (io/file base)
        uri (.toURI f)
        relative (.relativize uri (-> path io/file .toURI))]
    (.getPath relative)))

(defn create-tar [archive-name input-files]
  (let [out-stream (io/output-stream archive-name)
        tar-stream (TarArchiveOutputStream. out-stream)]
    ;; support filenames > 100 bytes
    (.setLongFileMode tar-stream TarArchiveOutputStream/LONGFILE_POSIX)
    (doseq [file-name input-files]
      (let [file (io/file file-name)]
        (doseq [f (if (.isDirectory file) (file-seq file) [file])]
          (when (.isFile f)
            (let [entry-name (relativise-path (FilenameUtils/getPath file-name) (-> f .getPath))
                  entry (.createArchiveEntry tar-stream f entry-name)]
              (.putArchiveEntry tar-stream entry)
              (when (.isFile f)
                (IOUtils/copy (io/input-stream f) tar-stream))
              (.closeArchiveEntry tar-stream))))))
    (.finish tar-stream)
    (.close tar-stream)
    archive-name))

;; Some functions below are from bob-cd
;;
;; Copyright 2018-2022 Rahul De
;; Source: https://github.com/bob-cd/bob/blob/main/runner/src/runner/engine.clj
(defn sh-tokenize
  "Tokenizes a shell command given as a string into the command and its args.

  Either returns a list of tokens or throws an IllegalStateException.
  Sample input: sh -c 'while sleep 1; do echo \\\"${RANDOM}\\\"; done'
  Output: [sh, -c, while sleep 1; do echo \"${RANDOM}\"; done]"
  [command]
  (let [[escaped? current-arg args state]
        (loop [cmd         command
               escaped?    false
               state       :no-token
               current-arg ""
               args        []]
          (if (or (nil? cmd) (zero? (count cmd)))
            [escaped? current-arg args state]
            (let [char ^Character (first cmd)]
              (if escaped?
                (recur (rest cmd) false state (str current-arg char) args)
                (case state
                  :single-quote       (if (= char \')
                                        (recur (rest cmd) escaped? :normal current-arg args)
                                        (recur (rest cmd) escaped? state (str current-arg char) args))
                  :double-quote       (case char
                                        \" (recur (rest cmd) escaped? :normal current-arg args)
                                        \\ (let [next (second cmd)]
                                             (if (or (= next \") (= next \\))
                                               (recur (drop 2 cmd) escaped? state (str current-arg next) args)
                                               (recur (drop 2 cmd) escaped? state (str current-arg char next) args)))
                                        (recur (rest cmd) escaped? state (str current-arg char) args))
                  (:no-token :normal) (case char
                                        \\ (recur (rest cmd) true :normal current-arg args)
                                        \' (recur (rest cmd) escaped? :single-quote current-arg args)
                                        \" (recur (rest cmd) escaped? :double-quote current-arg args)
                                        (if-not (Character/isWhitespace char)
                                          (recur (rest cmd) escaped? :normal (str current-arg char) args)
                                          (if (= state :normal)
                                            (recur (rest cmd) escaped? :no-token "" (conj args current-arg))
                                            (recur (rest cmd) escaped? state current-arg args))))
                  (throw (IllegalStateException. (format "Invalid shell command: %s, unexpected token %s found."
                                                         command
                                                         state))))))))]
    (if escaped?
      (conj args (str current-arg \\))
      (if (not= state :no-token)
        (conj args current-arg)
        args))))

(defn docker-pull [conn image]
  (let [client (c/client {:engine :podman
                          :category :libpod/images
                          :conn conn
                          :version api-version})]
    (c/invoke client {:op :ImagePullLibpod
                      :params {:reference image}
                      :throw-exceptions true})))

(defn commit-container
  "Creates a new image from a container
  Returns image identifier or a failure."
  [container-id conn]
  (f/try-all [client (c/client {:engine :podman
                                :category :libpod/commit
                                :conn conn
                                :version api-version})
              params {:container container-id}
              result (c/invoke client
                               {:op :ImageCommitLibpod
                                :params params
                                :throw-exceptions true})]
             (:Id result)
             (f/when-failed [err]
                            (log/errorf "Could not commit image: %s" (f/message err))
                            err)))

(defn stream-log
  "Stream logs of container with `id` through `reaction-fn`

  Each line of the docker output is fed to the `reaction-fn`. This function
  takes 2 arguments: the type of the log line and the string content.

  This also demuxes the docker headers of each line. The header of each log line
  has the following format:

  := [8]byte{STREAM_TYPE, 0, 0, 0, SIZE1, SIZE2, SIZE3, SIZE4}"
  [client id reaction-fn]
  (let [log-stream (c/invoke client {:op :ContainerLogsLibpod
                                     :params {:name id
                                              :follow true
                                              :stdout true
                                              :stderr true}
                                     :as :stream
                                     :throw-exceptions true})]
    (future
      (with-open [rdr (io/reader log-stream)]
        (loop [r (BufferedReader. rdr)]
          (let [log-type (.read r)]
            (when (> log-type -1)
              (.skip r 3)
              (let [buf (char-array 4)
                    _ (.read r buf 0 4)
                    bts (.getBytes (String. buf) (StandardCharsets/UTF_8))
                    byte-buf (ByteBuffer/wrap bts)
                    size (.getInt byte-buf)
                    line-buf (char-array size)
                    _ (.read r line-buf 0 size)
                    line (String. line-buf)]
                (reaction-fn line log-type))
              (recur r))))))))

(defn put-container-archive
  "Copies a tar input stream to a path in the container"
  [client id archive-input-stream path]
  (let [result (f/try*
                (with-open [xin archive-input-stream]
                  (c/invoke client
                            {:op :PutContainerArchiveLibpod
                             :params {:name id
                                      :path path}
                             :data xin
                             :throw-exceptions true})))]
    (when (f/failed? result)
      (log/errorf "Could not put archive in container: %s" result)
      result)))

(defn get-container-archive
  "Returns a tar stream of a path in the container by id."
  [client id path]
  (f/try-all [result (c/invoke client
                               {:op :ContainerArchiveLibpod
                                :params {:name id
                                         :path path}
                                :as :stream
                                :throw-exceptions true})]
             result
             (f/when-failed [err]
                            (log/errorf "Error fetching container archive: %s" err)
                            err)))

(defn inspect-container
  "Returns the container info by id."
  [id client]
  (f/try-all [result (c/invoke client
                               {:op               :ContainerInspectLibpod
                                :params           {:name id}
                                :throw-exceptions true})]
             result
             (f/when-failed [err]
                            (log/errorf "Error fetching container info: %s" err)
                            err)))

(defn create-tar-archive [arch-name path]
  (create-tar arch-name [path]))

(defn copy-resources-to-container!
  [client container-id resources artifact-path]
  (doseq [{:keys [name path create-tar] :or {create-tar? false}} resources]
    (let [source-path  (str artifact-path name)
          temp-archive (if create-tar
                         (create-tar "resource.tar" [source-path])
                         source-path)]
      (put-container-archive client container-id (io/input-stream temp-archive) path))))

(defn copy-artifacts-from-container!
  [client container-id artifacts artifact-path workdir]
  (doseq [{:keys [name path]} artifacts]
    (let [dest-path (str artifact-path "/" name)
          dir (str workdir "/" path)]
      (log/debugf "Streaming from container from %s to %s" dir dest-path)
      (f/try-all
       [stream (get-container-archive client container-id dir)]
       (io/copy stream (io/file dest-path))
       (f/when-failed
        [err]
        (throw (ex-info (format "Error in copying artifact %s from %s: %s"
                                name dir (f/message err)) {})))))))

(defn do-command!
  "Runs a command in a container from the `image`.
  Returns the result image and container-id as a tuple.
  `log-fn` is a function like #(prn \"CNT: \" %)"
  [client cmd image workdir log-fn conn]
  (f/try-all [result (c/invoke client
                               {:op   :ContainerCreateLibpod
                                :data {:image        image
                                       :command      (sh-tokenize (:cmd cmd))
                                       :env          {}
                                       :work_dir     (or (:workdir cmd) workdir)
                                       :cgroups_mode "disabled"}
                                :throw-exceptions
                                true})
              container-id (:Id result)

              _ (c/invoke client {:op               :ContainerStartLibpod
                                  :params           {:name container-id}
                                  :throw-exceptions true})

              _ (log/debugf "Attaching to container for logs, command: %s"
                            (:cmd cmd))
              _ (stream-log client container-id log-fn)

              status (c/invoke client {:op               :ContainerWaitLibpod
                                       :params           {:name container-id}
                                       :throw-exceptions true})

              image (commit-container container-id conn)]
             (do
               (when (zero? status)
                 (let [msg (format "Container %s exited with non-zero status %d"
                                   container-id status)]
                   (log/debug msg)
                   (f/fail msg)))
               [status image container-id])
             (f/when-failed [e]
                            (log/error "Error running command " e)
                            e)))

(defn get-error-message [e]
  (let [msg  (ex-message e)
        data (ex-data e)]
    (str msg ": " (:body data))))

(defn do-commands!
  "Runs an sequence of `commands` starting from `image`.
  Returns a vector of the results of each command."
  [client commands image workdir inline-logs? conn]
  (loop [cmds    commands
         results [{:img image :cmd nil :time (nos/current-time) :log nil}]]
    (let [[cmd & rst] cmds
          img         (-> results last :img)
          log-file    (java.io.File/createTempFile "log" ".txt")
          log-path    (.getAbsolutePath log-file)]
      (if (nil? cmd)
        [:success results]
        (f/if-let-failed?
         [;; this log command will log lines as a nested json array
          command-results
          (with-open [w (io/writer log-file)]
            (.write w "[")
            (let [result
                  (do-command! client cmd img workdir
                               #(.write w (str "[" %2 "," (json/encode %1) "],")) conn)]
              (.write w "[1,\"\"]]")
              result))]
         (do
           [:nos/error
            (get-error-message command-results)
            (conj results {:error (f/message command-results)
                           :time  (nos/current-time)
                           :cmd   cmd
                           :log   (cond-> log-path
                                    inline-logs? slurp)})])
         (let [[status image container] command-results
               new-results
               (conj
                results
                {:img       image
                 :status    status
                 :container container
                 :time      (nos/current-time)
                 :cmd       cmd
                 :log       (cond-> log-path
                              inline-logs? slurp)})]
           (if (pos? status)
             [:cmd-error new-results]
             (recur rst new-results))))))))

;; resources = copied from local disk to container before run
;; artifacts = copied from container to local disk after run
(defmethod nos/run-op
  :container/run
  [_
   {flow-id :id}
   [{:keys [image cmds conn artifacts resources work-dir env]
     :or   {conn      {:uri "http://localhost:8080"}
            work-dir  "/root"
            resources []
            artifacts []}}]]
  (f/try-all [_ (docker-pull conn image)
              _ (log/debugf "Pulled image %s" image)
              client (c/client {:engine   :podman
                                :category :libpod/containers
                                :conn     conn
                                :version  api-version})

              artifact-path (str "/tmp/nos-artifacts/" flow-id "/")
              _ (io/make-parents (str artifact-path "ignored.txt"))

              result (c/invoke client
                               {:op               :ContainerCreateLibpod
                                :data             {:image image
                                                   :env   env}
                                :throw-exceptions true})

              container-id (:Id result)

              _ (when (not-empty resources)
                  (log/debugf "Copying resources to container" )
                  (copy-resources-to-container! client container-id resources artifact-path))

              image (commit-container container-id conn)

              results (do-commands! client cmds image work-dir conn)

              _ (when (not-empty artifacts)
                  (log/debugf "Copying artifacts to host")
                  (copy-artifacts-from-container!
                   client
                   (-> results second last :container)
                   artifacts
                   artifact-path
                   work-dir))]
             results
             (f/when-failed [err]
                            (log/errorf ":container/run failed")
                            [:nos/error (f/message err)])))

(comment
  (flow/run-op :container/run nil
               [{:image     "alpine"
                 :resources [{:path "/tmp/logs" :name "root"}]
                 :artifacts [{:name "root" :dest "/tmp"}]
                 :cmds      ["touch /root/test" "ls -l /root/tmp"]
                 :conn      {:uri "http://localhost:8080"}}])

  (run-flow
   (flow/build  {:ops
                 [{:op   :container/run
                   :id   :clone
                   :args [{
                           :cmds      [{:cmd "git clone https://github.com/unraveled/dummy.git"}
                                       {:cmd "ls dummy"}]
                           :image     "registry.hub.docker.com/bitnami/git:latest"
                           :artifacts [{:name "dummy.tar" :path "/root"}]}]}
                  {:op   :container/run
                   :id   :list
                   :args [{:cmds      [{:cmd "ls -l dummy"}]
                           :image     "ubuntu"
                           :resources [{:name "dummy.tar" :dest "/root"}]}]}]})))
