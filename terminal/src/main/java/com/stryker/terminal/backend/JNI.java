package com.stryker.terminal.backend;

final class JNI {

  private static final String LOAD_ERROR;

  static {
    String error = null;
    try {
      System.loadLibrary("terminal");
    } catch (Throwable t) {
      error = (t.getMessage() != null) ? t.getMessage() : t.toString();
    }
    LOAD_ERROR = error;
  }

  static boolean isAvailable() {
    return LOAD_ERROR == null;
  }

  static String loadError() {
    return LOAD_ERROR;
  }

  public static native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int rows, int columns);

  public static native void setPtyWindowSize(int fd, int rows, int cols);

  public static native int waitFor(int processId);

  public static native void close(int fileDescriptor);

}
