package com.github.lzyzsd.imageloader;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.util.LruCache;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private ImageView mImageView;
    private Button mLoadButton;
    private LruCache<String, Bitmap> mBitmapLruCache;
    private Set<SoftReference<Bitmap>> mReusableBitmaps = Collections.synchronizedSet(new HashSet<SoftReference<Bitmap>>());


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final int cacheSize = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        mBitmapLruCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                super.entryRemoved(evicted, key, oldValue, newValue);
                mReusableBitmaps.add(new SoftReference<>(oldValue));//添加到mReusableBitmaps中，方便复用内存
            }
        };

        mImageView = (ImageView) findViewById(R.id.iv_image);
        mLoadButton = (Button) findViewById(R.id.btn_load_image);
        mLoadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadImage(R.drawable.big, mImageView);
            }
        });
    }

    //先是通过缓存里获取，如果没有，再去加载。
    private void loadImage(int resId, ImageView imageView) {
        Bitmap bitmap = mBitmapLruCache.get("resId:"+resId);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        //如果在加载，先取消
        cancelPotentialTask(imageView);

        //加载图片
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(getResources(), R.drawable.big, options);
        options.inSampleSize = calculateInSampleSize(options, 200, 200);
        options.inJustDecodeBounds = false;

        ImageTask imageTask = new ImageTask(mImageView, options);
        AsyncDrawable asyncDrawable = new AsyncDrawable(getResources(), null, imageTask);
        imageView.setImageDrawable(asyncDrawable);
        imageTask.execute(resId);
    }

    //如果在加载，先取消
    private void cancelPotentialTask(ImageView imageView) {
        if (imageView.getDrawable() instanceof AsyncDrawable) {
            AsyncDrawable asyncDrawable = (AsyncDrawable) imageView.getDrawable();
            ImageTask imageTask = asyncDrawable.getImageTask();
            if (imageTask != null) {
                if (imageTask.resId > 0) {
                    imageTask.cancel(true);
                }
            }
        }
    }


    private void addInBitmapOptions(BitmapFactory.Options option) {
        option.inMutable = true;//如果这图片可以复用
        Bitmap bitmap = findCandidate(option);
        if (bitmap != null) { //如果有可复用内存，
            option.inBitmap = bitmap; //直接使用。
        }
    }


    //查找
    private Bitmap findCandidate(BitmapFactory.Options options) {
        if (mReusableBitmaps != null && mReusableBitmaps.size() != 0) {
            synchronized (mReusableBitmaps) {
                Iterator<SoftReference<Bitmap>> iterator = mReusableBitmaps.iterator();
                while (iterator.hasNext()) {
                    Bitmap bitmap = iterator.next().get();
                    if (bitmap != null
                            && bitmap.isMutable()//可以复用
                            && candateMeets(bitmap, options)){//内存满足可以复用
                        iterator.remove();
                        return bitmap;
                    } else {
                        iterator.remove();
                    }
                }
            }
        }
        return null;
    }

    //判定是否可以复用。
    private boolean candateMeets(Bitmap bitmap, BitmapFactory.Options options) {
        //安卓4.4及以上，只要满足内存足够则可以复用。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int width = options.outWidth / options.inSampleSize;
            int height = options.outHeight / options.inSampleSize;
            return width * height * getPixelSize(options.inPreferredConfig) <= bitmap.getByteCount();
        }
        //否则，判断宽高是否可以相同。
        return options.outWidth == bitmap.getWidth() &&
                options.outHeight == bitmap.getHeight() &&
                options.inSampleSize == 1;
    }

    private int getPixelSize(Bitmap.Config config) {
        switch (config) {
            case ARGB_8888:
                return 4;
            case ARGB_4444:
                return 2;
            case RGB_565:
                return 2;
            case ALPHA_8:
                return 1;
            default:
                return 0;

        }
    }

    //计算缩放比例
    private int calculateInSampleSize(BitmapFactory.Options options, int requestWidth, int requestHeight) {
        int originWidth = options.outWidth;
        int originHeight = options.outHeight;
        int inSampleSize = 1;
        if (originHeight > requestHeight &&
                originWidth > requestWidth) {
            int halfHeight = originHeight / 2;
            int halfWidth = originWidth / 2;
            while (halfHeight / inSampleSize > requestHeight &&
                    halfWidth / inSampleSize > requestWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }


    //加载图片的AsyncTask
    class ImageTask extends AsyncTask<Integer, Void, Bitmap> {
        WeakReference<ImageView> mImageViewWeakReference;
        int resId = -1;
        BitmapFactory.Options mOptions;

        public ImageTask(ImageView imageView, BitmapFactory.Options options) {
            mImageViewWeakReference = new WeakReference<>(imageView);
            mOptions = options;
        }

        @Override
        protected Bitmap doInBackground(Integer... params) {
            resId = params[0];
            addInBitmapOptions(mOptions);
            return BitmapFactory.decodeResource(getResources(), resId, mOptions);
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            if (isCancelled()) {
                return;
            }

            //图片加入缓存
            mBitmapLruCache.put("resId:"+resId, bitmap);

            ImageView imageView = mImageViewWeakReference.get();
            if (imageView != null && getImageTask(imageView) == this) {//如果view不为null，并且加载这view的Task是这个，则显示
                imageView.setImageBitmap(bitmap);
            }
        }
    }

    //获取图片加载任务
    private ImageTask getImageTask(ImageView imageView) {
        Drawable drawable = imageView.getDrawable();
        if (drawable instanceof AsyncDrawable) {
            //返回ImageTask
            return ((AsyncDrawable) drawable).getImageTask();
        }
        return null;
    }

    //存储加载图片AsyncTask的任务
    public class AsyncDrawable extends BitmapDrawable {
        WeakReference<ImageTask> mImageTaskWeakReference;

        public AsyncDrawable(Resources resources, Bitmap bitmap, ImageTask imageTask) {
            super(bitmap);

            mImageTaskWeakReference = new WeakReference<ImageTask>(imageTask);
        }

        public ImageTask getImageTask() {
            return mImageTaskWeakReference.get();
        }
    }
}
