package com.example.fleshspread;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** config/flesh_spread.json. Дні рахуються від початку світу (24000 тіків = 1 день). */
public final class SpreadConfig {
    public boolean enabled = true;
    public List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld"));

    // --- гнізда в нових чанках (те, що знаходиш, коли досліджуєш карту) ---
    public double nestChancePerNewChunk = 0.006;  // ~ одне гніздо на 160x160 блоків
    public double nestDayGrowth = 0.05;           // +5% до шансу за кожен день
    public double nestMaxMultiplier = 4.0;        // стеля множника
    public int minDistanceFromSpawn = 96;         // не ближче до спавну світу
    public int minNestSpacing = 64;               // мінімальна відстань між гніздами
    public int patchRadius = 4;                   // радіус «зараженої плями» навколо нового гнізда

    // --- тиск: нові гнізда з'являються навколо гравця з часом ---
    public boolean pressureEnabled = true;
    public double pressureStartDay = 1.0;         // з якого дня
    public int pressureIntervalTicks = 4800;      // як часто (4 хв)
    public int pressureMinDistance = 48;
    public int pressureMaxDistance = 110;
    public int pressureCountRadius = 128;         // скільки гнізд навколо гравця рахуємо
    public int pressureBaseNests = 1;             // скільки гнізд тримаємо навколо гравця на старті
    public double pressureDaysPerExtraNest = 2.0; // +1 гніздо кожні N днів
    public int pressureMaxNests = 4;
    public boolean pressureWarning = true;
    public String pressureWarningText = "Десь неподалік щось ворушиться...";

    // --- дорослішання ---
    public double ripenDays = 2.0;                // малий інкубатор -> великий через N днів (0 = вимкнено)

    private static SpreadConfig instance = new SpreadConfig();
    public static SpreadConfig get() { return instance; }

    public static void load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("flesh_spread.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path)) {
                    SpreadConfig loaded = gson.fromJson(r, SpreadConfig.class);
                    if (loaded != null) instance = loaded;
                }
            }
            try (Writer w = Files.newBufferedWriter(path)) {
                gson.toJson(instance, w);
            }
        } catch (IOException | JsonParseException e) {
            FleshSpread.LOGGER.error("Не вдалося прочитати конфіг, використовую стандартний", e);
        }
    }
}
