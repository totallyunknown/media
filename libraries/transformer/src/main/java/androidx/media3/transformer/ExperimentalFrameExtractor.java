/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.media3.transformer;

import static androidx.media3.common.PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.usToMs;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.NullableType;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.PassthroughShaderProgram;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts decoded frames from {@link MediaItem}.
 *
 * <p>This class is experimental and will be renamed or removed in a future release.
 *
 * <p>Frame extractor instances must be accessed from a single application thread.
 */
/* package */ final class ExperimentalFrameExtractor implements AnalyticsListener {

  /** Stores an extracted and decoded video frame. */
  // TODO: b/350498258 - Add a Bitmap field to Frame.
  public static final class Frame {

    /** The presentation timestamp of the extracted frame, in milliseconds. */
    public final long presentationTimeMs;

    private Frame(long presentationTimeMs) {
      this.presentationTimeMs = presentationTimeMs;
    }
  }

  private final ExoPlayer player;
  private final Handler playerApplicationThreadHandler;

  /**
   * A {@link SettableFuture} representing the frame currently being extracted. Accessed on both the
   * {@linkplain ExoPlayer#getApplicationLooper() ExoPlayer application thread}, and the video
   * effects GL thread.
   */
  private final AtomicReference<@NullableType SettableFuture<Frame>>
      frameBeingExtractedFutureAtomicReference;

  /**
   * The last {@link SettableFuture} returned by {@link #getFrame(long)}. Accessed on the frame
   * extractor application thread.
   */
  private SettableFuture<Frame> lastRequestedFrameFuture;

  /**
   * The last {@link Frame} that was extracted successfully. Accessed on the {@linkplain
   * ExoPlayer#getApplicationLooper() ExoPlayer application thread}.
   */
  private @MonotonicNonNull Frame lastExtractedFrame;

  /**
   * Creates an instance.
   *
   * @param context {@link Context}.
   * @param mediaItem The {@link MediaItem} from which frames are extracted.
   */
  // TODO: b/350498258 - Support changing the MediaItem.
  // TODO: b/350498258 - Add configuration options such as SeekParameters.
  // TODO: b/350498258 - Support video effects.
  public ExperimentalFrameExtractor(Context context, MediaItem mediaItem) {
    player = new ExoPlayer.Builder(context).build();
    playerApplicationThreadHandler = new Handler(player.getApplicationLooper());
    lastRequestedFrameFuture = SettableFuture.create();
    // TODO: b/350498258 - Extracting the first frame is a workaround for ExoPlayer.setVideoEffects
    //   returning incorrect timestamps if we seek the player before rendering starts from zero.
    frameBeingExtractedFutureAtomicReference = new AtomicReference<>(lastRequestedFrameFuture);
    // TODO: b/350498258 - Refactor this and remove declaring this reference as initialized
    //  to satisfy the nullness checker.
    @SuppressWarnings("nullness:assignment")
    @Initialized
    ExperimentalFrameExtractor thisRef = this;
    playerApplicationThreadHandler.post(
        () -> {
          player.addAnalyticsListener(thisRef);
          player.setVideoEffects(buildVideoEffects());
          player.setMediaItem(mediaItem);
          player.setPlayWhenReady(false);
          player.prepare();
        });
  }

  /**
   * Extracts a representative {@link Frame} for the specified video position.
   *
   * @param positionMs The time position in the {@link MediaItem} for which a frame is extracted.
   * @return A {@link ListenableFuture} of the result.
   */
  public ListenableFuture<Frame> getFrame(long positionMs) {
    SettableFuture<Frame> frameSettableFuture = SettableFuture.create();
    // Process frameSettableFuture after lastRequestedFrameFuture completes.
    // If lastRequestedFrameFuture is done, the callbacks are invoked immediately.
    Futures.addCallback(
        lastRequestedFrameFuture,
        new FutureCallback<Frame>() {
          @Override
          public void onSuccess(Frame result) {
            playerApplicationThreadHandler.post(
                () -> {
                  lastExtractedFrame = result;
                  @Nullable PlaybackException playerError;
                  if (player.isReleased()) {
                    playerError =
                        new PlaybackException(
                            "The player is already released",
                            null,
                            ERROR_CODE_FAILED_RUNTIME_CHECK);
                  } else {
                    playerError = player.getPlayerError();
                  }
                  if (playerError != null) {
                    frameSettableFuture.setException(playerError);
                  } else {
                    checkState(
                        frameBeingExtractedFutureAtomicReference.compareAndSet(
                            null, frameSettableFuture));
                    player.seekTo(positionMs);
                  }
                });
          }

          @Override
          public void onFailure(Throwable t) {
            frameSettableFuture.setException(t);
          }
        },
        directExecutor());
    lastRequestedFrameFuture = frameSettableFuture;
    return lastRequestedFrameFuture;
  }

  /**
   * Releases the underlying resources. This method must be called when the frame extractor is no
   * longer required. The frame extractor must not be used after calling this method.
   */
  public void release() {
    // TODO: b/350498258 - Block the caller until exoPlayer.release() returns.
    playerApplicationThreadHandler.removeCallbacksAndMessages(null);
    playerApplicationThreadHandler.post(player::release);
  }

  // AnalyticsListener

  @Override
  public void onPlayerError(EventTime eventTime, PlaybackException error) {
    // Fail the next frame to be extracted. Errors will propagate to later pending requests via
    // Future callbacks.
    @Nullable
    SettableFuture<Frame> frameBeingExtractedFuture =
        frameBeingExtractedFutureAtomicReference.getAndSet(null);
    if (frameBeingExtractedFuture != null) {
      frameBeingExtractedFuture.setException(error);
    }
  }

  @Override
  public void onPositionDiscontinuity(
      EventTime eventTime,
      Player.PositionInfo oldPosition,
      Player.PositionInfo newPosition,
      @Player.DiscontinuityReason int reason) {
    if (oldPosition.equals(newPosition) && reason == DISCONTINUITY_REASON_SEEK) {
      // When the new seeking position resolves to the old position, no frames are rendered.
      // Repeat the previously returned frame.
      SettableFuture<Frame> frameBeingExtractedFuture =
          checkNotNull(frameBeingExtractedFutureAtomicReference.getAndSet(null));
      frameBeingExtractedFuture.set(checkNotNull(lastExtractedFrame));
    }
  }

  private ImmutableList<Effect> buildVideoEffects() {
    return ImmutableList.of(new FrameReader());
  }

  private final class FrameReader implements GlEffect {
    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) {
      // TODO: b/350498258 - Support HDR.
      return new FrameReadingGlShaderProgram();
    }
  }

  private final class FrameReadingGlShaderProgram extends PassthroughShaderProgram {
    @Override
    public void queueInputFrame(
        GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
      SettableFuture<Frame> frameBeingExtractedFuture =
          checkNotNull(frameBeingExtractedFutureAtomicReference.getAndSet(null));
      // TODO: b/350498258 - Read the input texture contents into a Bitmap.
      frameBeingExtractedFuture.set(new Frame(usToMs(presentationTimeUs)));
      // Drop frame: do not call outputListener.onOutputFrameAvailable().
      // Block effects pipeline: do not call inputListener.onReadyToAcceptInputFrame().
      // The effects pipeline will unblock and receive new frames when flushed after a seek.
      getInputListener().onInputFrameProcessed(inputTexture);
    }
  }
}
