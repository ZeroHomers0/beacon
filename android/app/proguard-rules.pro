
# WorkManager requests created before the package refactor retain this name.
-keep public class io.github.beacon.app.HttpPollingWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
