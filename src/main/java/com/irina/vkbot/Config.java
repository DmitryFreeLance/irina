package com.irina.vkbot;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Config {
  public final int groupId;
  public final String token;
  public final String apiVersion;
  public final Set<Integer> adminIds;
  public final String dbPath;
  public final int longPollWait;
  public final int pageSize;

  private Config(int groupId,
                 String token,
                 String apiVersion,
                 Set<Integer> adminIds,
                 String dbPath,
                 int longPollWait,
                 int pageSize) {
    this.groupId = groupId;
    this.token = token;
    this.apiVersion = apiVersion;
    this.adminIds = adminIds;
    this.dbPath = dbPath;
    this.longPollWait = longPollWait;
    this.pageSize = pageSize;
  }

  public static Config load() {
    int groupId = Integer.parseInt(env("VK_GROUP_ID", "0"));
    String token = env("VK_TOKEN", "");
    String apiVersion = env("VK_API_VERSION", "5.199");
    String dbPath = env("DB_PATH", "./bot.db");
    int longPollWait = Integer.parseInt(env("LONGPOLL_WAIT", "25"));
    int pageSize = Integer.parseInt(env("PAGE_SIZE", "8"));

    Set<Integer> adminIds = new HashSet<>();
    String adminRaw = env("ADMIN_IDS", "").trim();
    if (!adminRaw.isEmpty()) {
      Arrays.stream(adminRaw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .forEach(s -> adminIds.add(Integer.parseInt(s)));
    }

    if (groupId == 0 || token.isEmpty()) {
      throw new IllegalStateException("VK_GROUP_ID and VK_TOKEN are required");
    }

    return new Config(groupId, token, apiVersion, adminIds, dbPath, longPollWait, pageSize);
  }

  private static String env(String key, String def) {
    String val = System.getenv(key);
    if (val == null || val.isEmpty()) {
      return def;
    }
    return val;
  }
}
