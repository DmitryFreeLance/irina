package com.irina.vkbot;

import com.vk.api.sdk.client.TransportClient;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.GroupActor;
import com.vk.api.sdk.httpclient.HttpTransportClient;

public class BotApp {
  public static void main(String[] args) {
    Config config = Config.load();
    Db db = new Db(config.dbPath);
    db.init();

    TransportClient transportClient = new HttpTransportClient();
    VkApiClient vk = new VkApiClient(transportClient);
    GroupActor actor = new GroupActor(config.groupId, config.token);

    BotService bot = new BotService(config, db, vk, actor);
    bot.run();
  }
}
