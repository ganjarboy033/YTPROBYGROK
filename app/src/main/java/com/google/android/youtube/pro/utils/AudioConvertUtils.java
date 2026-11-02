package com.google.android.youtube.pro.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Extracts the audio track from a video file (MP4/WebM) and saves it as M4A (AAC).
 * Labeled as "MP3" in UI for user familiarity; output is high-quality AAC in .m4a container.
 */
public class AudioConvertUtils {

    private static final String TAG = "YTPRO_AUDIO";

    public interface ConvertCallback {
        void onSuccess(File outputFile);
        void onFailure(Exception e);
        void onProgress(int percent); // optional, can be ignored
    }

    /**
     * Convert video file to audio-only M4A.
     * @param deleteOriginal if true, deletes the source video after successful conversion
     * @param saveToMusic if true, saves to Music/YTPRO instead of Download/YTPRO
     */
    public static void convertToAudio(Context context,
                                      File videoFile,
                                      boolean deleteOriginal,
                                      boolean saveToMusic,
                                      ConvertCallback callback) {

        new Thread(() -> {
            MediaExtractor extractor = new MediaExtractor();
            MediaMuxer muxer = null;
            Uri outputUri = null;
            ParcelFileDescriptor pfd = null;
            File outputFile = null;

            try {
                if (!videoFile.exists()) {
                    throw new Exception("File not found: " + videoFile.getName());
                }

                extractor.setDataSource(videoFile.getAbsolutePath());

                // Find the first audio track
                int audioTrackIndex = -1;
                MediaFormat audioFormat = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrackIndex = i;
                        audioFormat = format;
                        break;
                    }
                }

                if (audioTrackIndex == -1 || audioFormat == null) {
                    throw new Exception("No audio track found in this video");
                }

                extractor.selectTrack(audioTrackIndex);

                // Prepare output file name
                String baseName = videoFile.getName();
                int dot = baseName.lastIndexOf('.');
                if (dot > 0) baseName = baseName.substring(0, dot);
                String outName = baseName + ".m4a";

                // Destination folder
                String relativePath = saveToMusic ? "Music/YTPRO" : "Download/YTPRO";
                File dir;
                if (saveToMusic) {
                    dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                } else {
                    dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                }
                dir = new File(dir, "YTPRO");
                if (!dir.exists()) dir.mkdirs();
                outputFile = new File(dir, outName);

                // Create muxer (audio only → MPEG-4 / M4A)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver resolver = context.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Audio.Media.DISPLAY_NAME, outName);
                    values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                    values.put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath);
                    values.put(MediaStore.Audio.Media.IS_PENDING, 1);

                    Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    outputUri = resolver.insert(collection, values);

                    if (outputUri == null) throw new Exception("Failed to create MediaStore entry");

                    pfd = resolver.openFileDescriptor(outputUri, "w");
                    if (pfd == null) throw new Exception("Cannot open output descriptor");
                    muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                } else {
                    muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                }

                int muxerTrackIndex = muxer.addTrack(audioFormat);
                muxer.start();

                ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024); // 1MB buffer
                

                // We use a simple BufferInfo-like approach
                android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();

                while (true) {
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;

                    bufferInfo.offset = 0;
                    bufferInfo.size = sampleSize;
                    bufferInfo.presentationTimeUs = extractor.getSampleTime();
                    bufferInfo.flags = extractor.getSampleFlags();

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo);
                    extractor.advance();
                }

                muxer.stop();
                muxer.release();
                muxer = null;

                // Finalize MediaStore
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    context.getContentResolver().update(outputUri, values, null, null);
                }

                if (pfd != null) {
                    pfd.close();
                    pfd = null;
                }

                Log.d(TAG, "Audio conversion success: " + outputFile.getName());

                // Delete original if requested
                if (deleteOriginal) {
                    deleteFile(context, videoFile);
                }

                final File result = outputFile;
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(result));
                }

            } catch (Exception e) {
                Log.e(TAG, "Conversion failed", e);

                // Cleanup partial output
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                    try {
                        context.getContentResolver().delete(outputUri, null, null);
                    } catch (Exception ignored) {}
                } else if (outputFile != null && outputFile.exists()) {
                    outputFile.delete();
                }

                if (callback != null) {
                    final Exception err = e;
                    new Handler(Looper.getMainLooper()).post(() -> callback.onFailure(err));
                }
            } finally {
                try { extractor.release(); } catch (Exception ignored) {}
                try {
                    if (muxer != null) {
                        muxer.stop();
                        muxer.release();
                    }
                } catch (Exception ignored) {}
                try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    private static void deleteFile(Context context, File file) {
        if (file == null || !file.exists()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Try both Downloads and Audio collections
            Uri[] collections = {
                    MediaStore.Downloads.getContentUri("external"),
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    MediaStore.Files.getContentUri("external")
            };
            for (Uri collection : collections) {
                try (Cursor cursor = context.getContentResolver().query(
                        collection,
                        new String[]{MediaStore.MediaColumns._ID},
                        MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                        new String[]{file.getName()},
                        null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        long id = cursor.getLong(0);
                        Uri uri = Uri.withAppendedPath(collection, String.valueOf(id));
                        context.getContentResolver().delete(uri, null, null);
                        Log.d(TAG, "Deleted via MediaStore: " + file.getName());
                        return;
                    }
                } catch (Exception ignored) {}
            }
            // Fallback
            file.delete();
        } else {
            file.delete();
        }
    }
}
