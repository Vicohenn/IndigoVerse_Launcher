package indigo.spawn;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

final class SpawnConfigGson {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private SpawnConfigGson() {}
}
