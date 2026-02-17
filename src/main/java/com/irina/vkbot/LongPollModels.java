package com.irina.vkbot;

import java.util.List;

class LongPollResponse {
  Integer failed;
  String ts;
  List<Update> updates;
}

class Update {
  String type;
  UpdateObject object;
}

class UpdateObject {
  LpMessage message;
}

class LpMessage {
  int id;
  int conversation_message_id;
  int from_id;
  int peer_id;
  String text;
  String payload;
  String ref;
  String ref_source;
  List<LpAttachment> attachments;
}

class LpAttachment {
  String type;
  LpDoc doc;
}

class LpDoc {
  int id;
  int owner_id;
  String access_key;
  String title;
  String url;
}
