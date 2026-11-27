package app.ezcall.android;

interface IShizukuAudioLauncher {
    void start(int port, in byte[] token) = 0;
    void destroy() = 16777114;
}
