/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import static androidx.media3.common.util.Assertions.checkState;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Wrapper around a single thread {@link ExecutorService} for executing {@link Task} instances.
 *
 * <p>Calls {@link ErrorListener#onError} for errors that occur during these tasks. The listener is
 * invoked from the {@link ExecutorService}.
 *
 * <p>{@linkplain #submitWithHighPriority(Task) High priority tasks} are always executed before
 * {@linkplain #submit(Task) default priority tasks}. Tasks with equal priority are executed in FIFO
 * order.
 */
/* package */ final class VideoFrameProcessingTaskExecutor {
  /**
   * Interface for tasks that may throw a {@link GlUtil.GlException} or {@link
   * VideoFrameProcessingException}.
   */
  interface Task {
    /** Runs the task. */
    void run() throws VideoFrameProcessingException, GlUtil.GlException;
  }

  /** Listener for errors. */
  interface ErrorListener {
    /**
     * Called when an exception occurs while executing submitted tasks.
     *
     * <p>If this is called, the calling {@link VideoFrameProcessingTaskExecutor} must immediately
     * be {@linkplain VideoFrameProcessingTaskExecutor#release} released}.
     */
    void onError(VideoFrameProcessingException exception);
  }

  private static final long EXECUTOR_SERVICE_TIMEOUT_MS = 500;

  private final boolean shouldShutdownExecutorService;
  private final ExecutorService singleThreadExecutorService;
  private final Future<Thread> threadFuture; // Used to identify the GL thread.
  private final ErrorListener errorListener;
  private final Object lock;

  @GuardedBy("lock")
  private final Queue<Task> highPriorityTasks;

  @GuardedBy("lock")
  private boolean shouldCancelTasks;

  /** Creates a new instance. */
  public VideoFrameProcessingTaskExecutor(
      ExecutorService singleThreadExecutorService,
      boolean shouldShutdownExecutorService,
      ErrorListener errorListener) {
    this.singleThreadExecutorService = singleThreadExecutorService;
    threadFuture = singleThreadExecutorService.submit(Thread::currentThread);
    this.shouldShutdownExecutorService = shouldShutdownExecutorService;
    this.errorListener = errorListener;
    lock = new Object();
    highPriorityTasks = new ArrayDeque<>();
  }

  /**
   * Submits the given {@link Task} to be executed after all pending tasks have completed.
   *
   * <p>Can be called on any thread.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void submit(Task task) {
    @Nullable RejectedExecutionException executionException = null;
    synchronized (lock) {
      if (shouldCancelTasks) {
        return;
      }
      try {
        wrapTaskAndSubmitToExecutorService(task, /* isFlushOrReleaseTask= */ false);
      } catch (RejectedExecutionException e) {
        executionException = e;
      }
    }

    if (executionException != null) {
      handleException(executionException);
    }
  }

  /**
   * Blocks the caller until the given {@link Task} has completed.
   *
   * <p>Can be called on any thread.
   */
  public void invoke(Task task) throws InterruptedException {
    // If running on the executor service thread, run synchronously.
    // Calling future.get() on the single executor thread would deadlock.
    if (isRunningOnVideoFrameProcessingThread()) {
      try {
        task.run();
      } catch (Exception e) {
        handleException(e);
      }
      return;
    }

    // Not running on the executor service thread. Block until task.run() returns.
    try {
      Future<?> taskFuture =
          singleThreadExecutorService.submit(
              () -> {
                try {
                  task.run();
                } catch (Exception e) {
                  handleException(e);
                }
              });
      taskFuture.get(EXECUTOR_SERVICE_TIMEOUT_MS, MILLISECONDS);
    } catch (RuntimeException | ExecutionException | TimeoutException e) {
      handleException(e);
    }
  }

  /**
   * Submits the given {@link Task} to be executed after the currently running task and all
   * previously submitted high-priority tasks have completed.
   *
   * <p>Tasks that were previously {@linkplain #submit(Task) submitted} without high-priority and
   * have not started executing will be executed after this task is complete.
   *
   * <p>Can be called on any thread.
   */
  public void submitWithHighPriority(Task task) {
    synchronized (lock) {
      if (shouldCancelTasks) {
        return;
      }
      highPriorityTasks.add(task);
    }
    // If the ExecutorService has non-started tasks, the first of these non-started tasks will run
    // the task passed to this method. Just in case there are no non-started tasks, submit another
    // task to run high-priority tasks.
    submit(() -> {});
  }

  /**
   * Flushes all scheduled tasks.
   *
   * <p>During flush, the {@code VideoFrameProcessingTaskExecutor} ignores the {@linkplain #submit
   * submission of new tasks}. The tasks that are submitted before flushing are either executed or
   * canceled when this method returns.
   *
   * <p>Can be called on any thread.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void flush() throws InterruptedException {
    synchronized (lock) {
      shouldCancelTasks = true;
      highPriorityTasks.clear();
    }

    CountDownLatch latch = new CountDownLatch(1);
    wrapTaskAndSubmitToExecutorService(
        () -> {
          synchronized (lock) {
            shouldCancelTasks = false;
          }
          latch.countDown();
        },
        /* isFlushOrReleaseTask= */ true);
    latch.await();
  }

  /**
   * Cancels remaining tasks, runs the given release task
   *
   * <p>If {@code shouldShutdownExecutorService} is {@code true}, shuts down the {@linkplain
   * ExecutorService background thread}.
   *
   * <p>This {@link VideoFrameProcessingTaskExecutor} instance must not be used after this method is
   * called.
   *
   * <p>Must not be called on the GL thread.
   *
   * @param releaseTask A {@link Task} to execute before shutting down the background thread.
   * @throws InterruptedException If interrupted while releasing resources.
   */
  public void release(Task releaseTask) throws InterruptedException {
    checkState(!isRunningOnVideoFrameProcessingThread());
    synchronized (lock) {
      shouldCancelTasks = true;
      highPriorityTasks.clear();
    }
    Future<?> unused =
        wrapTaskAndSubmitToExecutorService(releaseTask, /* isFlushOrReleaseTask= */ true);
    if (shouldShutdownExecutorService) {
      singleThreadExecutorService.shutdown();
      if (!singleThreadExecutorService.awaitTermination(
          EXECUTOR_SERVICE_TIMEOUT_MS, MILLISECONDS)) {
        errorListener.onError(
            new VideoFrameProcessingException(
                "Release timed out. OpenGL resources may not be cleaned up properly."));
      }
    }
  }

  public void verifyVideoFrameProcessingThread() {
    try {
      checkState(isRunningOnVideoFrameProcessingThread());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      handleException(e);
    }
  }

  private boolean isRunningOnVideoFrameProcessingThread() throws InterruptedException {
    Thread videoFrameProcessingThread;
    try {
      videoFrameProcessingThread = threadFuture.get(EXECUTOR_SERVICE_TIMEOUT_MS, MILLISECONDS);
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      handleException(e);
      return false;
    }
    return Thread.currentThread() == videoFrameProcessingThread;
  }

  private Future<?> wrapTaskAndSubmitToExecutorService(
      Task defaultPriorityTask, boolean isFlushOrReleaseTask) {
    return singleThreadExecutorService.submit(
        () -> {
          try {
            synchronized (lock) {
              if (shouldCancelTasks && !isFlushOrReleaseTask) {
                return;
              }
            }

            @Nullable Task nextHighPriorityTask;
            while (true) {
              synchronized (lock) {
                // Lock only polling to prevent blocking the public method calls.
                nextHighPriorityTask = highPriorityTasks.poll();
              }
              if (nextHighPriorityTask == null) {
                break;
              }
              nextHighPriorityTask.run();
            }
            defaultPriorityTask.run();
          } catch (Exception e) {
            handleException(e);
          }
        });
  }

  private void handleException(Exception exception) {
    synchronized (lock) {
      if (shouldCancelTasks) {
        // Ignore exception after cancelation as it can be caused by a previously reported exception
        // that is the reason for the cancelation.
        return;
      }
      shouldCancelTasks = true;
    }
    errorListener.onError(VideoFrameProcessingException.from(exception));
  }
}
