package com.irina.vkbot;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KeyboardBuilder {
  private static final Gson gson = new Gson();

  public static String keyboard(List<List<Button>> rows, boolean oneTime) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("one_time", oneTime);
    List<List<Map<String, Object>>> buttons = new ArrayList<>();
    for (List<Button> row : rows) {
      List<Map<String, Object>> rowButtons = new ArrayList<>();
      for (Button b : row) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "text");
        action.put("label", b.label);
        if (b.payload != null) {
          action.put("payload", gson.toJson(b.payload));
        }
        Map<String, Object> btn = new LinkedHashMap<>();
        btn.put("action", action);
        if (b.color != null) {
          btn.put("color", b.color);
        }
        rowButtons.add(btn);
      }
      buttons.add(rowButtons);
    }
    root.put("buttons", buttons);
    return gson.toJson(root);
  }

  public static Button button(String label, Map<String, Object> payload, String color) {
    Button b = new Button();
    b.label = label;
    b.payload = payload;
    b.color = color;
    return b;
  }

  public static Map<String, Object> payload(String cmd) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("cmd", cmd);
    return map;
  }

  public static Map<String, Object> payload(String cmd, String key, Object value) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("cmd", cmd);
    map.put(key, value);
    return map;
  }

  public static List<Button> rows(Button... buttons) {
    List<Button> row = new ArrayList<>();
    for (Button b : buttons) {
      row.add(b);
    }
    return row;
  }
}

class Button {
  String label;
  Map<String, Object> payload;
  String color;
}
