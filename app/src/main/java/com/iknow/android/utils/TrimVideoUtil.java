package com.iknow.android.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.coremedia.iso.boxes.Container;
import com.github.hiteshsondhi88.libffmpeg.ExecuteBinaryResponseHandler;
import com.github.hiteshsondhi88.libffmpeg.FFmpeg;
import com.github.hiteshsondhi88.libffmpeg.exceptions.FFmpegCommandAlreadyRunningException;
import com.googlecode.mp4parser.FileDataSourceViaHeapImpl;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import com.googlecode.mp4parser.authoring.tracks.CroppedTrack;
import com.iknow.android.interfaces.OnTrimVideoListener;
import com.iknow.android.models.VideoInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import iknow.android.utils.DateUtil;
import iknow.android.utils.DeviceUtil;
import iknow.android.utils.UnitConverter;
import iknow.android.utils.callback.SingleCallback;
import iknow.android.utils.thread.BackgroundExecutor;

public class TrimVideoUtil {

    private static final String TAG = TrimVideoUtil.class.getSimpleName();

    public static final int VIDEO_MAX_DURATION = 15;
    public static final int MIN_TIME_FRAME = 5;
    private static final int thumb_Width = (DeviceUtil.getDeviceWidth() - UnitConverter.dpToPx(20)) / VIDEO_MAX_DURATION;
    private static final int thumb_Height = UnitConverter.dpToPx(60);
    private static final long one_frame_time = 1000000;

    public static void trimVideo(final Context context, String inputFile, String outputFile, long startMs, long endMs, final OnTrimVideoListener callback) {
        final String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        final String outputName = "trimmedVideo_" + timeStamp + ".mp4";

        String start = convertSecondsToTime(startMs / 1000);
        String duration = convertSecondsToTime((endMs - startMs) / 1000);

        MediaMetadataRetriever metaRetriever = new MediaMetadataRetriever();


        inputFile = Uri.fromFile(new File(inputFile)).getEncodedPath();

        metaRetriever.setDataSource(context, Uri.fromFile(new File(inputFile)));

        String height = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
        String width = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);

        int heightInt = Integer.parseInt(height);
        int widthInt = Integer.parseInt(width);

        String newRes = (Math.round(heightInt/3)) + "x" + (Math.round(widthInt/3));

        String cmd = "-ss " + start + " -t " + duration + " -i " + inputFile + " -s "+newRes
                +" -acodec mp2 -strict -2 -ac 1 -ar 16000 -r 13 -ab 32000 " + outputFile + "/" + outputName;
        String[] command = cmd.split(" ");

        try {
            FFmpeg.getInstance(context).execute(command, new ExecuteBinaryResponseHandler() {
                @Override
                public void onFailure(String s) {
                    Log.e(TAG, s);
                    Toast.makeText(context, "Error "+s, Toast.LENGTH_LONG).show();
                }

                @Override
                public void onSuccess(String s) {
                    callback.onFinishTrim(null);
                }

                @Override
                public void onStart() {
                    callback.onStartTrim();
                }

                @Override
                public void onFinish() {
                }
            });
        } catch (FFmpegCommandAlreadyRunningException e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    public static void backgroundShootVideoThumb(final Context context, final Uri videoUri, final SingleCallback<ArrayList<Bitmap>, Integer> callback) {
        final ArrayList<Bitmap> thumbnailList = new ArrayList<>();
        BackgroundExecutor.execute(new BackgroundExecutor.Task("", 0L, "") {
               @Override
               public void execute() {
                   try {
                       MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
                       mediaMetadataRetriever.setDataSource(context, videoUri);

                       long videoLengthInMs = Long.parseLong(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000;
                       long numThumbs = videoLengthInMs < one_frame_time ? 1 : (videoLengthInMs / one_frame_time);
                       final long interval = videoLengthInMs / numThumbs;

                       for (long i = 0; i < numThumbs; ++i) {
                           Bitmap bitmap = mediaMetadataRetriever.getFrameAtTime(i * interval, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                           try {
                               bitmap = Bitmap.createScaledBitmap(bitmap, thumb_Width, thumb_Height, false);
                           } catch (Exception e) {
                               e.printStackTrace();
                           }
                           thumbnailList.add(bitmap);
                           if (thumbnailList.size() == 3) {
                               callback.onSingleCallback((ArrayList<Bitmap>) thumbnailList.clone(), (int) interval);
                               thumbnailList.clear();
                           }
                       }
                       if (thumbnailList.size() > 0) {
                           callback.onSingleCallback((ArrayList<Bitmap>) thumbnailList.clone(), (int) interval);
                           thumbnailList.clear();
                       }
                       mediaMetadataRetriever.release();
                   } catch (final Throwable e) {
                       Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), e);
                   }
               }
           }
        );

    }

    public static ArrayList<VideoInfo> getAllVideoFiles(Context mContext) {
        VideoInfo video;
        ArrayList<VideoInfo> videos = new ArrayList<>();
        ContentResolver contentResolver = mContext.getContentResolver();
        try {
            Cursor cursor = contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null,
                            null, null, MediaStore.Video.Media.DATE_MODIFIED + " desc");
            if(cursor != null) {
                while (cursor.moveToNext()) {
                    video = new VideoInfo();
                    if (cursor.getLong(cursor.getColumnIndex(MediaStore.Video.Media.DURATION)) != 0) {
                        video.setDuration(cursor.getLong(cursor.getColumnIndex(MediaStore.Video.Media.DURATION)));
                        video.setVideoPath(cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.DATA)));
                        video.setCreateTime(cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)));
                        video.setVideoName(cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)));
                        videos.add(video);
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return videos;
    }

    public static String getVideoFilePath(String url) {

        if (TextUtils.isEmpty(url) || url.length() < 5)
            return "";

        if (url.substring(0, 4).equalsIgnoreCase("http")) {
        } else
            url = "file://" + url;

        return url;
    }

    private static String convertSecondsToTime(long seconds) {
        String timeStr = null;
        int hour = 0;
        int minute = 0;
        int second = 0;
        if (seconds <= 0)
            return "00:00";
        else {
            minute = (int) seconds / 60;
            if (minute < 60) {
                second = (int) seconds % 60;
                timeStr = "00:" + unitFormat(minute) + ":" + unitFormat(second);
            } else {
                hour = minute / 60;
                if (hour > 99)
                    return "99:59:59";
                minute = minute % 60;
                second = (int) (seconds - hour * 3600 - minute * 60);
                timeStr = unitFormat(hour) + ":" + unitFormat(minute) + ":" + unitFormat(second);
            }
        }
        return timeStr;
    }

    private static String unitFormat(int i) {
        String retStr = null;
        if (i >= 0 && i < 10)
            retStr = "0" + Integer.toString(i);
        else
            retStr = "" + i;
        return retStr;
    }
}
