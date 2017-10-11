package com.google.firebase.cloud;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.grpc.GrpcTransportOptions;
import com.google.cloud.grpc.GrpcTransportOptions.ExecutorFactory;
import com.google.common.base.Strings;
import com.google.firebase.FirebaseApp;
import com.google.firebase.ImplFirebaseTrampolines;
import com.google.firebase.internal.FirebaseService;
import com.google.firebase.internal.GaeThreadFactory;
import com.google.firebase.internal.NonNull;
import com.google.firebase.internal.RevivingScheduledExecutor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * FirestoreClient provides access to Google Cloud Firestore. Use this API to obtain a
 * <code>com.google.cloud.firestore.Firestore</code> instance, which provides methods for
 * updating and querying data in Firestore.
 *
 * <p>A Google Cloud project ID is required to access Firestore. FirestoreClient determines the
 * project ID from the {@link com.google.firebase.FirebaseOptions} used to initialize the underlying
 * {@link FirebaseApp}. If that is not available, it examines the credentials used to initialize
 * the app. Finally it attempts to get the project ID by looking up the GCLOUD_PROJECT environment
 * variable. If a project ID cannot be determined by any of these methods, this API will throw
 * a runtime exception.
 */
public class FirestoreClient {

  private final Firestore firestore;

  private FirestoreClient(FirebaseApp app) {
    checkNotNull(app, "FirebaseApp must not be null");
    String projectId = ImplFirebaseTrampolines.getProjectId(app);
    checkArgument(!Strings.isNullOrEmpty(projectId),
        "Project ID is required for accessing Firestore. Use a service account credential or "
            + "set the project ID explicitly via FirebaseOptions. Alternatively you can also "
            + "set the project ID via the GCLOUD_PROJECT environment variable.");
    this.firestore = FirestoreOptions.newBuilder()
        .setCredentials(ImplFirebaseTrampolines.getCredentials(app))
        .setProjectId(projectId)
        .setTransportOptions(GrpcTransportOptions.newBuilder()
            .setExecutorFactory(new FirebaseExecutorFactory(app)).build())
        .build()
        .getService();
  }

  /**
   * Returns the Firestore instance associated with the default Firebase app.
   *
   * @return A non-null <code>com.google.cloud.firestore.Firestore</code> instance.
   */
  @NonNull
  public static Firestore getFirestore() {
    return getFirestore(FirebaseApp.getInstance());
  }

  /**
   * Returns the Firestore instance associated with the specified Firebase app.
   *
   * @param app A non-null {@link FirebaseApp}.
   * @return A non-null <code>com.google.cloud.firestore.Firestore</code> instance.
   */
  @NonNull
  public static Firestore getFirestore(FirebaseApp app) {
    return getInstance(app).firestore;
  }

  private static synchronized FirestoreClient getInstance(FirebaseApp app) {
    FirestoreClientService service = ImplFirebaseTrampolines.getService(app,
        SERVICE_ID, FirestoreClientService.class);
    if (service == null) {
      service = ImplFirebaseTrampolines.addService(app, new FirestoreClientService(app));
    }
    return service.getInstance();
  }

  private static class FirebaseExecutorFactory
      implements ExecutorFactory<ScheduledExecutorService> {

    private final FirebaseApp app;
    private boolean initialized;

    FirebaseExecutorFactory(FirebaseApp app) {
      this.app = checkNotNull(app, "app must not be null");
    }

    @Override
    public ScheduledExecutorService get() {
      ExecutorService executor = ImplFirebaseTrampolines.getExecutorService(app);
      if (executor instanceof ScheduledExecutorService) {
        // If the App has been initialized with a scheduled executor, simply reuse it. This enables
        // the developers to specify a single thread pool that will be used by both Firestore and
        // rest of the admin SDK.
        return (ScheduledExecutorService) executor;
      } else {
        // Otherwise, initialize a new scheduled executor using the specified ThreadFactory.
        ThreadFactory threadFactory = ImplFirebaseTrampolines.getThreadFactory(app);
        initialized = true;
        return new RevivingScheduledExecutor(threadFactory,
            "firebase-firestore-worker", GaeThreadFactory.isAvailable());
      }
    }

    @Override
    public void release(ScheduledExecutorService executor) {
      if (initialized) {
        executor.shutdownNow();
      }
    }
  }

  private static final String SERVICE_ID = FirestoreClient.class.getName();

  private static class FirestoreClientService extends FirebaseService<FirestoreClient> {

    FirestoreClientService(FirebaseApp app) {
      super(SERVICE_ID, new FirestoreClient(app));
    }

    @Override
    public void destroy() {
      // NOTE: We don't explicitly tear down anything here (for now). User won't be able to call
      // FirestoreClient.getFirestore() any more, but already created Firestore instances will
      // continue to work. Request Firestore team to provide a cleanup/teardown method on the
      // Firestore object.
    }
  }

}
