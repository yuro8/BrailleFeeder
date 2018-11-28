package com.example.android.braillefeeder.remote;

import com.example.android.braillefeeder.data.ArticleList;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;

/**
 * Created by Juraj on 3/19/2018.
 */

public interface NewsService {

    @GET("top-headlines") // ?country=us
    Call<ArticleList> getResponse(@QueryMap Map<String, String> options);
}
