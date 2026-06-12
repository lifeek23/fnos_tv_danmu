package com.fntv.app.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.util.LruCache;
import android.util.Log;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 简易图片加载器 - 使用 OkHttp（自动经过 AuthInterceptor 签名）
 * 兼容 Android 4.4+
 */
public class SimpleImageLoader {

    private static final String TAG = "ImageLoader";
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    private static final Map<String, ImageSize> sizeCache = new ConcurrentHashMap<>();

    public interface Callback {
        void onLoaded(ImageView view, int width, int height);
        void onFailed(ImageView view);
    }

    public static class ImageSize {
        public final int width;
        public final int height;

        ImageSize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    /** 加载图片（使用 OkHttpClient，自动携带认证头） */
    public static void load(String url, ImageView view, OkHttpClient client) {
        load(url, view, client, null);
    }

    /** 加载图片，并在成功后回传真实图片尺寸。 */
    public static void load(String url, ImageView view, OkHttpClient client, Callback callback) {
        if (url == null || url.isEmpty()) {
            view.setImageBitmap(null);
            view.setBackgroundColor(0xFF333333);
            if (callback != null) callback.onFailed(view);
            return;
        }

        Bitmap cached = cache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            if (callback != null) callback.onLoaded(view, cached.getWidth(), cached.getHeight());
            return;
        }

        // Android 4.4 AsyncTask 默认串行，用线程池实现并行下载
        new ImageLoadTask(view, client, callback).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, url);
    }

    public static ImageSize getCachedSize(String url) {
        if (url == null) return null;
        Bitmap bitmap = cache.get(url);
        if (bitmap != null) return new ImageSize(bitmap.getWidth(), bitmap.getHeight());
        return sizeCache.get(url);
    }

    public static void clearCache() {
        cache.evictAll();
        sizeCache.clear();
    }

    private static class ImageLoadTask extends AsyncTask<String, Void, Bitmap> {
        private final ImageView imageView;
        private final OkHttpClient client;
        private final Callback callback;
        private String url;

        ImageLoadTask(ImageView imageView, OkHttpClient client, Callback callback) {
            this.imageView = imageView;
            this.client = client;
            this.callback = callback;
        }

        @Override
        protected Bitmap doInBackground(String... params) {
            String urlStr = params[0];
            url = urlStr;
            try {
                Request request = new Request.Builder()
                        .url(urlStr)
                        .build();

                Response response = client.newCall(request).execute();
                int code = response.code();
                String contentType = response.header("Content-Type");
                long len = response.body() != null ? response.body().contentLength() : -1;
                // Log.d(TAG, "HTTP " + code + " type=" + contentType + " len=" + len + " " + urlStr);

                if (code != 200) return null;

                // 不是图片就打印并跳过
                if (contentType != null && !contentType.contains("image")) {
                    String body = response.body() != null ? response.body().string() : "";
                    String snippet = body.length() > 200 ? body.substring(0, 200) : body;
                    Log.w(TAG, "NOT_IMAGE: " + snippet);
                    return null;
                }

                // 读取图片字节
                InputStream input = response.body().byteStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = input.read(buf)) != -1) baos.write(buf, 0, n);
                input.close();
                byte[] imageData = baos.toByteArray();

                // 三重解码尝试
                Bitmap bitmap = tryDecode(imageData);

                if (bitmap != null) {
                    cache.put(urlStr, bitmap);
                    sizeCache.put(urlStr, new ImageSize(bitmap.getWidth(), bitmap.getHeight()));
                    Log.d(TAG, "OK (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                } else {
                    Log.e(TAG, "DECODE_FAILED");
                }
                return bitmap;
            } catch (Exception e) {
                Log.e(TAG, "FAIL: " + e.getMessage());
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
                if (callback != null) callback.onLoaded(imageView, bitmap.getWidth(), bitmap.getHeight());
            } else {
                imageView.setBackgroundColor(0xFF444444);
                if (callback != null) callback.onFailed(imageView);
            }
        }
    }

    private static Bitmap tryDecode(byte[] data) {
        Bitmap bmp = null;
        if (bmp == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inPurgeable = true;
            o.inInputShareable = true;
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o); } catch (Exception ignored) {}
        }
        if (bmp == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inPurgeable = true;
            o.inInputShareable = true;
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o); } catch (Exception ignored) {}
        }
        if (bmp == null) {
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length); } catch (Exception ignored) {}
        }
        return bmp;
    }
}
