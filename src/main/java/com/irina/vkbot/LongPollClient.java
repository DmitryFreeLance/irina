package com.irina.vkbot;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class LongPollClient {
  private final OkHttpClient http = new OkHttpClient.Builder()
    .readTimeout(70, TimeUnit.SECONDS)
    .callTimeout(75, TimeUnit.SECONDS)
    .build();
  private final Gson gson = new Gson();

  public LongPollResponse poll(String serverUrl, String key, String ts, int wait) {
    String url = serverUrl +
      "?act=a_check" +
      "&key=" + key +
      "&ts=" + ts +
      "&wait=" + wait +
      "&mode=2&version=3";

    Request request = new Request.Builder().url(url).get().build();
    try (Response response = http.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new IOException("Unexpected code " + response);
      }
      String body = response.body() != null ? response.body().string() : "{}";
      return gson.fromJson(body, LongPollResponse.class);
    } catch (SocketTimeoutException e) {
      LongPollResponse resp = new LongPollResponse();
      resp.ts = ts;
      resp.updates = Collections.emptyList();
      return resp;
    } catch (IOException e) {
      throw new RuntimeException("Long poll failed", e);
    }
  }
}
