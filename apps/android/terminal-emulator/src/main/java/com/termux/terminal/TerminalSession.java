package com.termux.terminal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Terminal session bridged to a remote PTY (Hypurr host openShell).
 * <p>
 * Vendored from Termux terminal-emulator (Apache-2.0, originally jackpal Android
 * Terminal Emulator). Local JNI subprocess support was removed; bytes arrive via
 * {@link #append(byte[], int)} from the WebSocket and leave via {@link Writer}.
 */
public final class TerminalSession extends TerminalOutput {

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;

    /** 1 while open, -1 after {@link #finishIfRunning()}. */
    int mShellPid = 1;

    int mShellExitStatus;

    public String mSessionName;

    private final Integer mTranscriptRows;
    private final Writer mWriter;

    public interface Writer {
        void write(byte[] data, int offset, int count);
    }

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client, Writer writer) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        this.mWriter = writer;
    }

    /** Compatibility constructor used by older Termux call sites (unused in Hypurr). */
    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this(transcriptRows, client, (data, offset, count) -> { /* no local shell */ });
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    public void updateSize(int columns, int rows) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows);
        } else {
            mEmulator.resize(columns, rows);
        }
    }

    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    public void initializeEmulator(int columns, int rows) {
        mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);
        mShellPid = 1;
        if (mClient != null) mClient.onTextChanged(this);
    }

    /** Feed bytes from the remote PTY into the VT emulator (main thread). */
    public void append(byte[] data, int length) {
        if (mEmulator == null || length <= 0) return;
        mEmulator.append(data, length);
        notifyScreenUpdate();
    }

    public void append(byte[] data) {
        append(data, data.length);
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid > 0 && mWriter != null && count > 0) {
            mWriter.write(data, offset, count);
        }
    }

    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }
        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;
        if (codePoint <= 0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= 0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= 0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    protected void notifyScreenUpdate() {
        if (mClient != null) mClient.onTextChanged(this);
    }

    public void reset() {
        if (mEmulator != null) {
            mEmulator.reset();
            notifyScreenUpdate();
        }
    }

    public void finishIfRunning() {
        if (isRunning()) {
            cleanupResources(0);
            if (mClient != null) mClient.onSessionFinished(this);
        }
    }

    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        if (mClient != null) mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        if (mClient != null) mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    public String getCwd() {
        return null;
    }

    /** Append a short exit banner then mark finished (main thread). */
    public void notifyRemoteExit(int exitCode) {
        if (mEmulator != null) {
            String exitDescription = "\r\n[Process completed";
            if (exitCode > 0) exitDescription += " (code " + exitCode + ")";
            else if (exitCode < 0) exitDescription += " (signal " + (-exitCode) + ")";
            exitDescription += "]";
            byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
            mEmulator.append(bytesToWrite, bytesToWrite.length);
            notifyScreenUpdate();
        }
        cleanupResources(exitCode);
        if (mClient != null) mClient.onSessionFinished(this);
    }
}
