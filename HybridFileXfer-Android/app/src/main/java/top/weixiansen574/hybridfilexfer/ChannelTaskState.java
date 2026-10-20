package top.weixiansen574.hybridfilexfer;

/** Main-thread channel state, kept separate from Android views and translated text. */
final class ChannelTaskState {
    enum Kind { IDLE, TASK, COMPLETE, ERROR }

    static final class Task {
        final String path;
        final boolean upload;
        final long offset, length, fileSize;

        Task(String path, boolean upload, long offset, long length, long fileSize) {
            this.path = path;
            this.upload = upload;
            this.offset = offset;
            this.length = length;
            this.fileSize = fileSize;
        }

        long firstByte() { return length == 0 ? offset : offset + 1; }
        long lastByte() { return offset + length; }
    }

    private boolean accepting;
    private Kind kind = Kind.IDLE;
    private Task task;
    private String message = "";

    void begin() {
        accepting = true;
        clearTask();
    }

    boolean task(String path, boolean upload, long offset, long length, long fileSize) {
        if (!accepting || kind == Kind.COMPLETE || kind == Kind.ERROR || path == null
                || offset < 0 || length < 0 || fileSize < 0 || offset > fileSize
                || length > fileSize - offset) return false;
        kind = Kind.TASK;
        task = new Task(path, upload, offset, length, fileSize);
        message = "";
        return true;
    }

    boolean idle() {
        // A worker's finally callback must not erase its completion/error callback.
        if (!accepting || kind != Kind.TASK) return false;
        clearTask();
        return true;
    }

    boolean complete(String message) {
        if (!accepting || kind == Kind.ERROR) return false;
        kind = Kind.COMPLETE;
        task = null;
        this.message = message == null ? "" : message;
        return true;
    }

    boolean error(String message) {
        if (!accepting) return false;
        kind = Kind.ERROR;
        task = null;
        this.message = message == null ? "" : message;
        return true;
    }

    void end() {
        accepting = false;
        if (kind == Kind.TASK) clearTask();
    }

    private void clearTask() {
        kind = Kind.IDLE;
        task = null;
        message = "";
    }

    Kind kind() { return kind; }
    Task task() { return task; }
    String message() { return message; }
}
