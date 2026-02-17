package com.irina.vkbot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.GroupActor;
import com.vk.api.sdk.objects.groups.responses.GetLongPollServerResponse;
import com.vk.api.sdk.objects.groups.responses.IsMemberResponse;
import com.vk.api.sdk.objects.messages.MessageAttachment;
import com.vk.api.sdk.objects.messages.MessageAttachmentType;
import com.vk.api.sdk.objects.docs.Doc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public class BotService {
  private final Config config;
  private final Db db;
  private final VkApiClient vk;
  private final GroupActor actor;
  private final LongPollClient longPoll = new LongPollClient();

  private static final String STATE_ADD_TITLE = "ADMIN_ADD_TITLE";
  private static final String STATE_ADD_DESC = "ADMIN_ADD_DESC";
  private static final String STATE_ADD_TYPE = "ADMIN_ADD_TYPE";
  private static final String STATE_ADD_FILE = "ADMIN_ADD_FILE";
  private static final String STATE_ADD_URL = "ADMIN_ADD_URL";
  private static final String STATE_EDIT_SELECT = "ADMIN_EDIT_SELECT";
  private static final String STATE_EDIT_FIELD = "ADMIN_EDIT_FIELD";
  private static final String STATE_EDIT_VALUE = "ADMIN_EDIT_VALUE";
  private static final String STATE_DELETE_SELECT = "ADMIN_DELETE_SELECT";
  private static final String STATE_LINK_SELECT = "ADMIN_LINK_SELECT";
  private static final String STATE_BROADCAST = "ADMIN_BROADCAST";
  private static final String STATE_BROADCAST_CONFIRM = "ADMIN_BROADCAST_CONFIRM";

  public BotService(Config config, Db db, VkApiClient vk, GroupActor actor) {
    this.config = config;
    this.db = db;
    this.vk = vk;
    this.actor = actor;
  }

  public void run() {
    while (true) {
      try {
        GetLongPollServerResponse server = vk.groups().getLongPollServer(actor, config.groupId).execute();
        String ts = server.getTs();
        while (true) {
          LongPollResponse response = longPoll.poll(server.getServer(), server.getKey(), ts, config.longPollWait);
          if (response.failed != null) {
            if (response.failed == 1) {
              ts = response.ts;
              continue;
            }
            break;
          }
          ts = response.ts;
          if (response.updates == null) {
            continue;
          }
          for (Update update : response.updates) {
            handleUpdate(update);
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
        sleep(2000);
      }
    }
  }

  private void handleUpdate(Update update) {
    if (!"message_new".equals(update.type)) {
      return;
    }
    LpMessage msg = update.object != null ? update.object.message : null;
    if (msg == null) {
      return;
    }

    int userId = msg.from_id;
    int peerId = msg.peer_id;
    boolean isAdmin = config.adminIds.contains(userId);
    db.upsertUser(userId, isAdmin);

    PayloadData payload = parsePayload(msg.payload);
    String text = msg.text != null ? msg.text.trim() : "";

    AdminState adminState = isAdmin ? db.getAdminState(userId) : null;
    if (isAdmin && adminState != null) {
      if (handleAdminState(peerId, msg, text, payload, adminState)) {
        return;
      }
    }

    if (isAdmin && payload != null && payload.cmd != null && payload.cmd.startsWith("admin_")) {
      if (handleAdminPayload(peerId, userId, payload)) {
        return;
      }
      showAdminMenu(peerId);
      return;
    }

    if (isAdmin && isAdminCommand(text, payload)) {
      showAdminMenu(peerId);
      return;
    }

    if (isStart(text, payload)) {
      handleStart(userId, peerId, msg);
      return;
    }

    if (payload != null && "check_sub".equals(payload.cmd)) {
      handleCheckSubscription(userId, peerId);
      return;
    }

    if (payload != null && "magnet".equals(payload.cmd)) {
      int magnetId = payload.id;
      handleMagnetSelect(userId, peerId, magnetId);
      return;
    }

    if (payload != null && "list".equals(payload.cmd)) {
      showMagnetList(peerId, payload.page);
      return;
    }

    sendMessage(peerId, "Напишите /start, чтобы получить материалы.", null, null);
  }

  private void handleStart(int userId, int peerId, LpMessage msg) {
    db.logEvent(userId, "start", null);

    String ref = msg.ref;
    if (ref != null && !ref.isEmpty()) {
      db.setPendingRef(userId, ref);
    }

    boolean subscribed = isMember(userId);
    db.setSubscribed(userId, subscribed);
    if (subscribed) {
      db.logEvent(userId, "subscribed", null);
      String pendingRef = getPendingRef(userId);
      if (pendingRef != null) {
        Magnet magnet = db.getMagnetByRef(pendingRef);
        db.setPendingRef(userId, null);
        if (magnet != null) {
          sendMagnet(peerId, userId, magnet);
          return;
        }
      }
      showMagnetList(peerId, 0);
    } else {
      askToSubscribe(peerId);
    }
  }

  private void handleCheckSubscription(int userId, int peerId) {
    boolean subscribed = isMember(userId);
    db.setSubscribed(userId, subscribed);
    if (subscribed) {
      db.logEvent(userId, "subscribed", null);
      String pendingRef = getPendingRef(userId);
      if (pendingRef != null) {
        Magnet magnet = db.getMagnetByRef(pendingRef);
        db.setPendingRef(userId, null);
        if (magnet != null) {
          sendMagnet(peerId, userId, magnet);
          return;
        }
      }
      showMagnetList(peerId, 0);
    } else {
      sendMessage(peerId, "Похоже, подписки пока нет. Подпишитесь на сообщество и нажмите «Проверить подписку».", subscribeKeyboard(), null);
    }
  }

  private void handleMagnetSelect(int userId, int peerId, int magnetId) {
    boolean subscribed = isMember(userId);
    db.setSubscribed(userId, subscribed);
    if (!subscribed) {
      askToSubscribe(peerId);
      return;
    }
    Magnet magnet = db.getMagnetById(magnetId);
    if (magnet == null || !magnet.isActive) {
      sendMessage(peerId, "Этот материал больше не доступен. Выберите другой.", null, null);
      showMagnetList(peerId, 0);
      return;
    }
    sendMagnet(peerId, userId, magnet);
  }

  private void sendMagnet(int peerId, int userId, Magnet magnet) {
    StringBuilder text = new StringBuilder();
    text.append("Ваш материал: ").append(magnet.title).append("\n");
    if (magnet.description != null && !magnet.description.isEmpty()) {
      text.append(magnet.description).append("\n");
    }
    if ("URL".equalsIgnoreCase(magnet.type)) {
      text.append("Ссылка: ").append(magnet.url);
      sendMessage(peerId, text.toString(), null, null);
    } else {
      if (magnet.attachment == null || magnet.attachment.isEmpty()) {
        if (magnet.url != null && !magnet.url.isEmpty()) {
          sendMessage(peerId, text.toString(), null, null);
          sendMessage(peerId, "Файл недоступен, вот ссылка на скачивание:\n" + magnet.url, null, null);
        } else {
          sendMessage(peerId, text.toString(), null, null);
          sendMessage(peerId, "Файл недоступен. Попробуйте позже или обратитесь к администратору.", null, null);
        }
      } else {
        boolean sent = sendMessageSafe(peerId, text.toString(), null, magnet.attachment);
        if (!sent) {
          if (magnet.url != null && !magnet.url.isEmpty()) {
            sendMessage(peerId, "Не удалось отправить файл. Вот ссылка на скачивание:\n" + magnet.url, null, null);
          } else {
            sendMessage(peerId, "Не удалось отправить файл. Попробуйте позже или обратитесь к администратору.", null, null);
          }
        }
      }
    }
    sendMessage(peerId, "Если хотите посмотреть другие материалы, нажмите «Обновить материалы».", refreshKeyboard(), null);
    db.logEvent(userId, "magnet_sent", magnet.id);
  }

  private void showMagnetList(int peerId, int page) {
    int total = db.countMagnets(true);
    if (total == 0) {
      sendMessage(peerId, "Пока нет доступных материалов. Попробуйте позже.", null, null);
      return;
    }
    int offset = page * config.pageSize;
    List<Magnet> magnets = db.listMagnets(true, offset, config.pageSize);
    List<List<Button>> rows = new ArrayList<>();
    for (Magnet m : magnets) {
      rows.add(KeyboardBuilder.rows(
        KeyboardBuilder.button(m.title, KeyboardBuilder.payload("magnet", "id", m.id), "primary")
      ));
    }

    int lastPage = (total - 1) / config.pageSize;
    if (lastPage > 0) {
      List<Button> nav = new ArrayList<>();
      if (page > 0) {
        nav.add(KeyboardBuilder.button("<< Назад", KeyboardBuilder.payload("list", "page", page - 1), "secondary"));
      }
      if (page < lastPage) {
        nav.add(KeyboardBuilder.button("Дальше >>", KeyboardBuilder.payload("list", "page", page + 1), "secondary"));
      }
      if (!nav.isEmpty()) {
        rows.add(nav);
      }
    }

    rows.add(KeyboardBuilder.rows(
      KeyboardBuilder.button("Обновить материалы", KeyboardBuilder.payload("list", "page", 0), "secondary")
    ));

    String keyboard = KeyboardBuilder.keyboard(rows, false);
    sendMessage(peerId, "Выберите материал:", keyboard, null);
  }

  private void askToSubscribe(int peerId) {
    String text = "Чтобы получить материалы, подпишитесь на наше сообщество. После подписки нажмите «Проверить подписку».";
    sendMessage(peerId, text, subscribeKeyboard(), null);
  }

  private String subscribeKeyboard() {
    List<List<Button>> rows = new ArrayList<>();
    rows.add(KeyboardBuilder.rows(
      KeyboardBuilder.button("Проверить подписку", KeyboardBuilder.payload("check_sub"), "positive")
    ));
    return KeyboardBuilder.keyboard(rows, true);
  }

  private String refreshKeyboard() {
    List<List<Button>> rows = new ArrayList<>();
    rows.add(KeyboardBuilder.rows(
      KeyboardBuilder.button("Обновить материалы", KeyboardBuilder.payload("list", "page", 0), "secondary")
    ));
    return KeyboardBuilder.keyboard(rows, false);
  }

  private boolean isMember(int userId) {
    try {
      IsMemberResponse resp = vk.groups().isMember(actor, String.valueOf(config.groupId))
        .userId(userId)
        .execute();
      return resp == IsMemberResponse.YES;
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }

  private void showAdminMenu(int peerId) {
    List<List<Button>> rows = new ArrayList<>();
    rows.add(KeyboardBuilder.rows(
      KeyboardBuilder.button("➕ Добавить", KeyboardBuilder.payload("admin_add"), "positive"),
      KeyboardBuilder.button("✏️ Редактировать", KeyboardBuilder.payload("admin_edit"), "primary")
    ));
    rows.add(KeyboardBuilder.rows(
      KeyboardBuilder.button("🗑 Удалить", KeyboardBuilder.payload("admin_delete"), "negative"),
      KeyboardBuilder.button("🔗 Ссылка", KeyboardBuilder.payload("admin_link"), "secondary")
    ));
    rows.add(KeyboardBuilder.rows(
      KeyboardBuilder.button("📊 Статистика", KeyboardBuilder.payload("admin_stats"), "secondary"),
      KeyboardBuilder.button("📣 Рассылка", KeyboardBuilder.payload("admin_broadcast"), "primary")
    ));

    String keyboard = KeyboardBuilder.keyboard(rows, false);
    sendMessage(peerId, "Админ-панель. Выберите действие:", keyboard, null);
  }

  private boolean handleAdminState(int peerId, LpMessage msg, String text, PayloadData payload, AdminState state) {
    String st = state.state;
    JsonObject data = state.data != null ? JsonParser.parseString(state.data).getAsJsonObject() : new JsonObject();

    if (STATE_ADD_TITLE.equals(st)) {
      if (text.isEmpty()) {
        sendMessage(peerId, "Введите название материала.", null, null);
        return true;
      }
      data.addProperty("title", text);
      db.setAdminState(msg.from_id, STATE_ADD_DESC, data);
      sendMessage(peerId, "Кратко опишите материал (или отправьте «-»).", null, null);
      return true;
    }

    if (STATE_ADD_DESC.equals(st)) {
      data.addProperty("description", "-".equals(text) ? "" : text);
      db.setAdminState(msg.from_id, STATE_ADD_TYPE, data);

      List<List<Button>> rows = new ArrayList<>();
      rows.add(KeyboardBuilder.rows(
        KeyboardBuilder.button("Файл", KeyboardBuilder.payload("admin_add_type", "type", "DOC"), "primary"),
        KeyboardBuilder.button("Ссылка", KeyboardBuilder.payload("admin_add_type", "type", "URL"), "secondary")
      ));
      sendMessage(peerId, "Выберите тип материала:", KeyboardBuilder.keyboard(rows, true), null);
      return true;
    }

    if (STATE_ADD_TYPE.equals(st)) {
      if (payload == null || !"admin_add_type".equals(payload.cmd)) {
        sendMessage(peerId, "Выберите тип через кнопки ниже.", null, null);
        return true;
      }
      String type = payload.type;
      data.addProperty("type", type);
      if ("DOC".equalsIgnoreCase(type)) {
        db.setAdminState(msg.from_id, STATE_ADD_FILE, data);
        sendMessage(peerId, "Отправьте файл документом (PDF/архив/видео до 200 МБ).", null, null);
      } else {
        db.setAdminState(msg.from_id, STATE_ADD_URL, data);
        sendMessage(peerId, "Пришлите ссылку на материал.", null, null);
      }
      return true;
    }

    if (STATE_ADD_FILE.equals(st)) {
      DocInfo info = extractDocInfo(msg);
      if (info == null) {
        sendMessage(peerId, "Не вижу файла. Отправьте материал документом.", null, null);
        return true;
      }
      System.out.println("[DEBUG] add-file: attachment=" + info.attachment + " url=" + info.url +
        " msgId=" + msg.id + " convMsgId=" + msg.conversation_message_id + " peerId=" + msg.peer_id);
      Magnet m = new Magnet();
      m.title = data.get("title").getAsString();
      m.description = data.get("description").getAsString();
      m.type = "DOC";
      m.attachment = info.attachment;
      m.url = info.url;
      m.refCode = RefCode.generate();
      m.isActive = true;
      int id = db.createMagnet(m);
      db.clearAdminState(msg.from_id);
      sendMessage(peerId, "Материал добавлен. ID: " + id, null, null);
      boolean ok = sendMessageSafe(peerId, "Проверка выдачи файла (должен прийти документ).", null, m.attachment);
      if (!ok) {
        sendMessage(peerId, "Проверка выдачи не прошла. Попробуйте переотправить файл.", null, null);
      }
      return true;
    }

    if (STATE_ADD_URL.equals(st)) {
      if (text.isEmpty()) {
        sendMessage(peerId, "Пришлите ссылку на материал.", null, null);
        return true;
      }
      Magnet m = new Magnet();
      m.title = data.get("title").getAsString();
      m.description = data.get("description").getAsString();
      m.type = "URL";
      m.url = text;
      m.attachment = null;
      m.refCode = RefCode.generate();
      m.isActive = true;
      int id = db.createMagnet(m);
      db.clearAdminState(msg.from_id);
      sendMessage(peerId, "Материал добавлен. ID: " + id, null, null);
      return true;
    }

    if (STATE_EDIT_SELECT.equals(st)) {
      Integer id = extractId(payload, text, "admin_edit_select");
      if (id == null) {
        sendMessage(peerId, "Выберите материал кнопкой ниже или введите его ID.", null, null);
        return true;
      }
      data.addProperty("id", id);
      db.setAdminState(msg.from_id, STATE_EDIT_FIELD, data);
      showEditFieldMenu(peerId);
      return true;
    }

    if (STATE_EDIT_FIELD.equals(st)) {
      if (payload == null || !"admin_edit_field".equals(payload.cmd)) {
        sendMessage(peerId, "Выберите поле для редактирования.", null, null);
        return true;
      }
      data.addProperty("field", payload.field);
      db.setAdminState(msg.from_id, STATE_EDIT_VALUE, data);

      String field = payload.field;
      if ("attachment".equals(field)) {
        sendMessage(peerId, "Отправьте новый файл документом.", null, null);
      } else if ("active".equals(field)) {
        List<List<Button>> rows = new ArrayList<>();
        rows.add(KeyboardBuilder.rows(
          KeyboardBuilder.button("Активен", KeyboardBuilder.payload("admin_edit_active", "value", 1), "positive"),
          KeyboardBuilder.button("Скрыт", KeyboardBuilder.payload("admin_edit_active", "value", 0), "negative")
        ));
        sendMessage(peerId, "Выберите статус:", KeyboardBuilder.keyboard(rows, true), null);
      } else {
        sendMessage(peerId, "Введите новое значение.", null, null);
      }
      return true;
    }

    if (STATE_EDIT_VALUE.equals(st)) {
      int id = data.get("id").getAsInt();
      String field = data.get("field").getAsString();
      Magnet m = db.getMagnetById(id);
      if (m == null) {
        db.clearAdminState(msg.from_id);
        sendMessage(peerId, "Материал не найден.", null, null);
        return true;
      }

      if ("attachment".equals(field)) {
        DocInfo info = extractDocInfo(msg);
        if (info == null) {
          sendMessage(peerId, "Не вижу файла. Отправьте документ.", null, null);
          return true;
        }
        m.attachment = info.attachment;
        m.url = info.url;
        m.type = "DOC";
      } else if ("url".equals(field)) {
        if (text.isEmpty()) {
          sendMessage(peerId, "Введите ссылку.", null, null);
          return true;
        }
        m.url = text;
        m.type = "URL";
      } else if ("title".equals(field)) {
        if (text.isEmpty()) {
          sendMessage(peerId, "Введите название.", null, null);
          return true;
        }
        m.title = text;
      } else if ("description".equals(field)) {
        m.description = "-".equals(text) ? "" : text;
      } else if ("active".equals(field)) {
        if (payload == null || !"admin_edit_active".equals(payload.cmd)) {
          sendMessage(peerId, "Выберите статус кнопкой.", null, null);
          return true;
        }
        m.isActive = payload.value == 1;
      }

      db.updateMagnet(m);
      db.clearAdminState(msg.from_id);
      sendMessage(peerId, "Готово. Материал обновлен.", null, null);
      return true;
    }

    if (STATE_DELETE_SELECT.equals(st)) {
      Integer id = extractId(payload, text, "admin_delete_select");
      if (id == null) {
        sendMessage(peerId, "Выберите материал кнопкой ниже или введите его ID.", null, null);
        return true;
      }
      db.deleteMagnet(id);
      db.clearAdminState(msg.from_id);
      sendMessage(peerId, "Материал удален.", null, null);
      return true;
    }

    if (STATE_LINK_SELECT.equals(st)) {
      Integer id = extractId(payload, text, "admin_link_select");
      if (id == null) {
        sendMessage(peerId, "Выберите материал кнопкой ниже или введите его ID.", null, null);
        return true;
      }
      Magnet m = db.getMagnetById(id);
      db.clearAdminState(msg.from_id);
      if (m == null) {
        sendMessage(peerId, "Материал не найден.", null, null);
        return true;
      }
      String link = "https://vk.com/club" + config.groupId + "?ref=" + m.refCode;
      sendMessage(peerId, "Уникальная ссылка для «" + m.title + "»:\n" + link, null, null);
      return true;
    }

    if (STATE_BROADCAST.equals(st)) {
      String attachment = extractDocAttachment(msg);
      if (text.isEmpty() && attachment == null) {
        sendMessage(peerId, "Отправьте текст и/или файл для рассылки.", null, null);
        return true;
      }
      data.addProperty("text", text);
      if (attachment != null) {
        data.addProperty("attachment", attachment);
      }
      db.setAdminState(msg.from_id, STATE_BROADCAST_CONFIRM, data);
      List<List<Button>> rows = new ArrayList<>();
      rows.add(KeyboardBuilder.rows(
        KeyboardBuilder.button("Отправить", KeyboardBuilder.payload("admin_broadcast_send"), "positive"),
        KeyboardBuilder.button("Отмена", KeyboardBuilder.payload("admin_broadcast_cancel"), "negative")
      ));
      sendMessage(peerId, "Подтвердите рассылку:", KeyboardBuilder.keyboard(rows, true), null);
      return true;
    }

    if (STATE_BROADCAST_CONFIRM.equals(st)) {
      if (payload == null || (!"admin_broadcast_send".equals(payload.cmd) && !"admin_broadcast_cancel".equals(payload.cmd))) {
        sendMessage(peerId, "Выберите «Отправить» или «Отмена».", null, null);
        return true;
      }
      if ("admin_broadcast_cancel".equals(payload.cmd)) {
        db.clearAdminState(msg.from_id);
        sendMessage(peerId, "Рассылка отменена.", null, null);
        return true;
      }
      String textMsg = data.has("text") ? data.get("text").getAsString() : "";
      String attachment = data.has("attachment") ? data.get("attachment").getAsString() : null;
      db.clearAdminState(msg.from_id);
      doBroadcast(textMsg, attachment, peerId);
      return true;
    }

    if (payload != null && handleAdminPayload(peerId, msg.from_id, payload)) {
      return true;
    }

    return false;
  }

  private boolean handleAdminPayload(int peerId, int userId, PayloadData payload) {
    if (payload == null || payload.cmd == null) {
      return false;
    }
    switch (payload.cmd) {
      case "admin_add":
        db.setAdminState(userId, STATE_ADD_TITLE, new JsonObject());
        sendMessage(peerId, "Введите название материала.", null, null);
        return true;
      case "admin_edit":
        startEditFlow(peerId, userId);
        return true;
      case "admin_delete":
        startDeleteFlow(peerId, userId);
        return true;
      case "admin_link":
        startLinkFlow(peerId, userId);
        return true;
      case "admin_stats":
        showStats(peerId);
        return true;
      case "admin_broadcast":
        db.setAdminState(userId, STATE_BROADCAST, new JsonObject());
        sendMessage(peerId, "Отправьте текст и/или файл для рассылки.", null, null);
        return true;
      default:
        return false;
    }
  }

  private void startEditFlow(int peerId, int userId) {
    int total = db.countMagnets(false);
    if (total == 0) {
      sendMessage(peerId, "Материалов пока нет.", null, null);
      return;
    }
    db.setAdminState(userId, STATE_EDIT_SELECT, new JsonObject());
    showAdminMagnetList(peerId, "Выберите материал для редактирования:", "admin_edit_select");
  }

  private void startDeleteFlow(int peerId, int userId) {
    int total = db.countMagnets(false);
    if (total == 0) {
      sendMessage(peerId, "Материалов пока нет.", null, null);
      return;
    }
    db.setAdminState(userId, STATE_DELETE_SELECT, new JsonObject());
    showAdminMagnetList(peerId, "Выберите материал для удаления:", "admin_delete_select");
  }

  private void startLinkFlow(int peerId, int userId) {
    int total = db.countMagnets(false);
    if (total == 0) {
      sendMessage(peerId, "Материалов пока нет.", null, null);
      return;
    }
    db.setAdminState(userId, STATE_LINK_SELECT, new JsonObject());
    showAdminMagnetList(peerId, "Выберите материал для ссылки:", "admin_link_select");
  }

  private void showAdminMagnetList(int peerId, String title, String cmd) {
    List<Magnet> magnets = db.listMagnets(false, 0, 20);
    List<List<Button>> rows = new ArrayList<>();
    for (Magnet m : magnets) {
      rows.add(KeyboardBuilder.rows(
        KeyboardBuilder.button(m.id + ". " + m.title, KeyboardBuilder.payload(cmd, "id", m.id), "primary")
      ));
    }
    sendMessage(peerId, title, KeyboardBuilder.keyboard(rows, false), null);
  }

  private void showEditFieldMenu(int peerId) {
    List<List<Button>> rows = new ArrayList<>();
    rows.add(KeyboardBuilder.rows(
      KeyboardBuilder.button("Название", KeyboardBuilder.payload("admin_edit_field", "field", "title"), "primary"),
      KeyboardBuilder.button("Описание", KeyboardBuilder.payload("admin_edit_field", "field", "description"), "secondary")
    ));
    rows.add(KeyboardBuilder.rows(
      KeyboardBuilder.button("Файл", KeyboardBuilder.payload("admin_edit_field", "field", "attachment"), "primary"),
      KeyboardBuilder.button("Ссылка", KeyboardBuilder.payload("admin_edit_field", "field", "url"), "secondary")
    ));
    rows.add(KeyboardBuilder.rows(
      KeyboardBuilder.button("Активность", KeyboardBuilder.payload("admin_edit_field", "field", "active"), "secondary")
    ));
    sendMessage(peerId, "Что изменить?", KeyboardBuilder.keyboard(rows, false), null);
  }

  private void showStats(int peerId) {
    Stats stats = db.getStats();
    List<MagnetStat> magnetStats = db.getMagnetStats();

    StringBuilder sb = new StringBuilder();
    sb.append("Статистика:\n");
    sb.append("Стартов всего: ").append(stats.startsTotal).append("\n");
    sb.append("Уникальных стартов: ").append(stats.startsUnique).append("\n");
    sb.append("Подписались (уник.): ").append(stats.subscribedUnique).append("\n\n");
    sb.append("Скачивания по материалам:\n");

    if (magnetStats.isEmpty()) {
      sb.append("Нет данных");
    } else {
      for (MagnetStat ms : magnetStats) {
        sb.append(ms.id).append(". ").append(ms.title).append(" — ").append(ms.downloads).append("\n");
      }
    }

    sendMessage(peerId, sb.toString(), null, null);
  }

  private void doBroadcast(String text, String attachment, int adminPeerId) {
    List<Integer> users = db.listAllUsers();
    int sent = 0;
    for (int userId : users) {
      try {
        sendMessage(userId, text, null, attachment);
        sent++;
      } catch (Exception e) {
        e.printStackTrace();
      }
      sleep(60);
    }
    sendMessage(adminPeerId, "Рассылка завершена. Отправлено: " + sent, null, null);
  }

  private boolean isAdminCommand(String text, PayloadData payload) {
    String t = text.toLowerCase();
    if ("/admin".equals(t) || "админ".equals(t) || "admin".equals(t)) {
      return true;
    }
    return payload != null && payload.cmd != null && payload.cmd.startsWith("admin_");
  }

  private boolean isStart(String text, PayloadData payload) {
    String t = text.toLowerCase();
    if ("/start".equals(t) || "start".equals(t) || "старт".equals(t) || "начать".equals(t) || "меню".equals(t)) {
      return true;
    }
    return payload != null && Objects.equals(payload.cmd, "start");
  }

  private PayloadData parsePayload(String payload) {
    if (payload == null || payload.isEmpty()) {
      return null;
    }
    try {
      JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
      PayloadData pd = new PayloadData();
      if (obj.has("cmd")) {
        pd.cmd = obj.get("cmd").getAsString();
      }
      if (obj.has("id")) {
        pd.id = obj.get("id").getAsInt();
      }
      if (obj.has("page")) {
        pd.page = obj.get("page").getAsInt();
      }
      if (obj.has("type")) {
        pd.type = obj.get("type").getAsString();
      }
      if (obj.has("field")) {
        pd.field = obj.get("field").getAsString();
      }
      if (obj.has("value")) {
        pd.value = obj.get("value").getAsInt();
      }
      return pd;
    } catch (Exception e) {
      return null;
    }
  }

  private Integer extractId(PayloadData payload, String text, String expectedCmd) {
    if (payload != null && expectedCmd.equals(payload.cmd)) {
      return payload.id;
    }
    if (text == null) {
      return null;
    }
    String trimmed = text.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    try {
      return Integer.parseInt(trimmed);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private DocInfo extractDocInfo(LpMessage msg) {
    DocInfo info = extractDocInfoFromLp(msg);
    if (info != null) {
      return info;
    }
    if (msg.id > 0) {
      return extractDocInfoFromApi(msg.id);
    }
    if (msg.conversation_message_id > 0) {
      return extractDocInfoFromConversation(msg.peer_id, msg.conversation_message_id);
    }
    return null;
  }

  private DocInfo extractDocInfoFromLp(LpMessage msg) {
    if (msg.attachments == null) {
      return null;
    }
    for (LpAttachment att : msg.attachments) {
      if ("doc".equals(att.type) && att.doc != null) {
        LpDoc doc = att.doc;
        String base = "doc" + doc.owner_id + "_" + doc.id;
        if (doc.access_key != null && !doc.access_key.isEmpty()) {
          base = base + "_" + doc.access_key;
        }
        DocInfo info = new DocInfo();
        info.attachment = base;
        info.url = doc.url;
        return info;
      }
    }
    return null;
  }

  private DocInfo extractDocInfoFromApi(int messageId) {
    try {
      var resp = vk.messages().getById(actor, messageId)
        .groupId(config.groupId)
        .execute();
      if (resp.getItems() == null || resp.getItems().isEmpty()) {
        return null;
      }
      var msg = resp.getItems().get(0);
      if (msg.getAttachments() == null) {
        return null;
      }
      for (MessageAttachment att : msg.getAttachments()) {
        if (att.getType() == MessageAttachmentType.DOC && att.getDoc() != null) {
          Doc doc = att.getDoc();
          String base = "doc" + doc.getOwnerId() + "_" + doc.getId();
          if (doc.getAccessKey() != null && !doc.getAccessKey().isEmpty()) {
            base = base + "_" + doc.getAccessKey();
          }
          DocInfo info = new DocInfo();
          info.attachment = base;
          info.url = doc.getUrl() != null ? doc.getUrl().toString() : null;
          return info;
        }
      }
      return null;
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  private DocInfo extractDocInfoFromConversation(int peerId, int conversationMessageId) {
    try {
      var resp = vk.messages().getByConversationMessageId(actor, peerId, conversationMessageId)
        .groupId(config.groupId)
        .execute();
      if (resp.getItems() == null || resp.getItems().isEmpty()) {
        return null;
      }
      var msg = resp.getItems().get(0);
      if (msg.getAttachments() == null) {
        return null;
      }
      for (MessageAttachment att : msg.getAttachments()) {
        if (att.getType() == MessageAttachmentType.DOC && att.getDoc() != null) {
          Doc doc = att.getDoc();
          String base = "doc" + doc.getOwnerId() + "_" + doc.getId();
          if (doc.getAccessKey() != null && !doc.getAccessKey().isEmpty()) {
            base = base + "_" + doc.getAccessKey();
          }
          DocInfo info = new DocInfo();
          info.attachment = base;
          info.url = doc.getUrl() != null ? doc.getUrl().toString() : null;
          return info;
        }
      }
      return null;
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  private String extractDocAttachment(LpMessage msg) {
    DocInfo info = extractDocInfo(msg);
    return info != null ? info.attachment : null;
  }

  private String getPendingRef(int userId) {
    User u = db.getUser(userId);
    return u != null ? u.pendingRef : null;
  }

  private boolean sendMessageSafe(int peerId, String text, String keyboard, String attachment) {
    try {
      int randomId = ThreadLocalRandom.current().nextInt();
      var query = vk.messages().send(actor)
        .peerId(peerId)
        .randomId(randomId)
        .message(text);
      if (keyboard != null) {
        query.unsafeParam("keyboard", keyboard);
      }
      if (attachment != null) {
        System.out.println("[DEBUG] send: peer=" + peerId + " attachment=" + attachment);
        query.attachment(attachment);
      }
      query.execute();
      return true;
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }

  private void sendMessage(int peerId, String text, String keyboard, String attachment) {
    sendMessageSafe(peerId, text, keyboard, attachment);
  }

  private void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

class PayloadData {
  String cmd;
  int id;
  int page;
  String type;
  String field;
  int value;
}

class RefCode {
  public static String generate() {
    String rand = Integer.toString(ThreadLocalRandom.current().nextInt(100000, 999999), 36);
    return "m" + System.currentTimeMillis() + rand;
  }
}

class DocInfo {
  String attachment;
  String url;
}
