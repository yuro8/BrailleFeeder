package com.example.android.braillefeeder;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.android.braillefeeder.data.ApiUtils;
import com.example.android.braillefeeder.data.model.Article;
import com.example.android.braillefeeder.data.ArticleList;
import com.example.android.braillefeeder.data.model.ArticleSettings;
import com.example.android.braillefeeder.remote.NewsService;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import butterknife.BindView;
import butterknife.ButterKnife;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;


public class MainActivity extends Activity implements VoiceControl.VoiceControlListener, TextRead.TextReadListener, VisionService.VisionServiceListener{

    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final int PERMISSIONS_REQUEST_CAMERA = 2;

    private List<Article> mArticleList;

    private NewsService mNewsService;
    private TextRead mTextRead;
    private SpeechToText mSpeechToTextService;
    private SpeechRecorder mSpeechRecorder;
    private CameraService mCameraService;
    private VisionService mVisionService;

    private Handler mHandlerCamera;
    private HandlerThread mThreadCamera;

    private VoiceControl mVoiceControl = new VoiceControl(this);

    private final SpeechRecorder.SpeechRecorderCallback mRecorderCallback = new SpeechRecorder.SpeechRecorderCallback() {

        @Override
        public void onRecordStarted() {
            if( mSpeechToTextService != null) {
                mSpeechToTextService.startRecognizing(mSpeechRecorder.getSampleRate());
            }
        }

        @Override
        public void onRecordListening(byte[] data, int size) {
            if( mSpeechToTextService != null) {
                mSpeechToTextService.recognize(data, size);
            }
        }

        @Override
        public void onRecordEnded() {
            if( mSpeechToTextService != null) {
                mSpeechToTextService.finishRecognizing();
            }
        }
    };

    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Log.e("ServiceConnection", "onServiceConnected");

            mSpeechToTextService = SpeechToText.from(iBinder);
            mSpeechToTextService.addListener(mSpeechServiceListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.e("ServiceConnection", "onServiceDisconnected");

            mSpeechToTextService = null;
        }
    };

    @BindView(R.id.button)
    Button mButton;

    @BindView(R.id.button_camera)
    Button mCameraButton;

    @BindView(R.id.textview)
    TextView mTextView;

    @BindView(R.id.imageview)
    ImageView mImageView;

    @BindView(R.id.button_recorder_on)
    Button mButtonRecorderOn;

    @BindView(R.id.button_recorder_off)
    Button mButtonRecorderOff;

    private String api = "";
    private Map<String, String> apiMap = new HashMap<>();
    private String locale;

    private int articlePosition;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        mNewsService = ApiUtils.getNewService();
        mThreadCamera = new HandlerThread("CameraThread");
        mThreadCamera.start();
        mHandlerCamera = new Handler(mThreadCamera.getLooper());

        mCameraService = CameraService.getInstance();
        mCameraService.initializeCamera(this, mHandlerCamera, mOnImageAvailableListener);

        mVisionService = new VisionService(this, this);

        locale = "us";
        loadAnswers();
        mButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                changeArticle(1);
            }
        });

        mCameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mCameraService.takePicture();
            }
        });

        mButtonRecorderOn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startVoiceRecorder();
            }
        });

        mButtonRecorderOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopVoiceRecorder();
            }
        });

        mTextRead = new TextRead(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int i) {
                mTextRead.getTextToSpeech().setLanguage(new Locale(locale));
                mTextRead.speakText(getResources().getString(R.string.welcome_speech));
            }
        }, this);


    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull  int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceRecorder();
            } else {
                finish();
            }
        } else if (requestCode == PERMISSIONS_REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            } else {
                finish();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        bindService(new Intent(this, SpeechToText.class), mServiceConnection, BIND_AUTO_CREATE);

        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        } else {
            startVoiceRecorder();
        }
        permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.CAMERA);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSIONS_REQUEST_CAMERA);
            return;
        }
    }

    @Override
    protected void onStop() {
        stopVoiceRecorder();

        if( mSpeechToTextService != null) {
            mSpeechToTextService.removeListener(mSpeechServiceListener);
        }
        unbindService(mServiceConnection);
        mSpeechToTextService = null;

        mCameraService.shutdown();
        mThreadCamera.quitSafely();

        super.onStop();
    }

    private void startVoiceRecorder() {
        if (mSpeechRecorder != null) {
            mSpeechRecorder.stopRecorder();
        }
        mSpeechRecorder = new SpeechRecorder(mRecorderCallback);
        mSpeechRecorder.startRecorder();
    }

    private void stopVoiceRecorder() {
        if (mSpeechRecorder != null) {
            mSpeechRecorder.stopRecorder();
            mSpeechRecorder = null;
        }
    }

    private final SpeechToText.SpeechToTextListener mSpeechServiceListener =
            new SpeechToText.SpeechToTextListener() {
                @Override
                public void onSpeechRecognized(final String text, final boolean isFinal) {
                    if (isFinal) {
                        mSpeechRecorder.dismiss();
                        Log.d("SpeechToTextListener", "isFinal");
                    }
                    if (text != null && !TextUtils.isEmpty(text)) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinal) {
                                    Log.d("Main", text);
                                    mTextView.setText(text);
                                    VoiceControl.recognizeCommand(text);
                                }
                            }
                        });
                    }
                }
            };


    public void loadAnswers() {
        buildQuery();
        mNewsService.getResponse(apiMap).enqueue(new Callback<ArticleList>() {
            @Override
            public void onResponse(Call<ArticleList> call, Response<ArticleList> response) {
                if(response.isSuccessful()) {
                    Log.d("loadAnswers", response.raw().request().url().toString());
                    ArticleList articleList = response.body();
                    mArticleList = articleList.getArticleList();
                    articlePosition = 0;
                }else {
                    Log.d("loadAnswers", response.raw().request().url().toString());
                    Log.e("MainActivity", "Response unsuccesful: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<ArticleList> call, Throwable t) {
                Log.e("MainActivity", "Response failure: " + t.toString());
            }
        });
    }

    private void buildQuery() {
        apiMap.put("country", locale);
        apiMap.put("apiKey", api);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if( mTextRead != null) {
            mTextRead.shutDownSpeaker();
        }
    }

    @Override
    public void onRecognizeCommand(ArticleSettings articleSettings) {
        Log.d("onRecognizeCommand", "onRecognizeCommand");
        if( articleSettings.getAbout() != null) {
            apiMap.put("q", articleSettings.getAbout());
            apiMap.remove("category");
        } else if( articleSettings.getCategory() != null) {
            apiMap.put("category", articleSettings.getCategory());
            apiMap.remove("q");
        }
        loadAnswers();
    }

    @Override
    public void onHelpCommand() {
        mTextRead.speakText(getResources().getString(R.string.help_speech));
    }

    @Override
    public void onLocaleChangeCommand() {
        locale = "sk";
        mTextRead.getTextToSpeech().setLanguage(new Locale(locale));
        loadAnswers();
    }

    @Override
    public void onNextArticleCommand() {
        changeArticle(1);
    }

    @Override
    public void onPreviousArticleCommand() {
        changeArticle(-1);
    }

    @Override
    public void onTextReadCompleted() {
        startVoiceRecorder();
    }

    @Override
    public void onTextReadStarted() {
        stopVoiceRecorder();
    }

    public void changeArticle(int pos) {
        if( mArticleList != null) {
            if( articlePosition + pos < mArticleList.size() && articlePosition + pos >= 0) {
                Log.d("changeArticle", articlePosition + "");
                articlePosition += pos;
                Log.d("changeArticle", articlePosition + "");
                mTextRead.speakText(mArticleList.get(articlePosition));
            }
        } else {
            loadAnswers();
        }
    }

    private ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader imageReader) {
                    Image image = imageReader.acquireLatestImage();
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.capacity()];
                    buffer.get(bytes);
                    final Bitmap bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);
                    image.close();
                    mVisionService.callCloudVision(bitmapImage);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mImageView.setImageBitmap(bitmapImage);
                        }
                    });

                }
    };

    @Override
    public void onVisionCompleted(String result) {
        mTextRead.speakText(result);
        Log.d("onVisionCompleted", result);
    }
}