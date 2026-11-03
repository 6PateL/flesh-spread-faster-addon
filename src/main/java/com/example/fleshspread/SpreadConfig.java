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

    // --- рейди зараження ---
    public boolean raidsEnabled = true;
    public double raidStartDay = 2.0;             // перший рейд не раніше цього дня
    public int raidIntervalDays = 2;              // рейд кожні N днів
    public int raidSearchRadius = 220;            // цілі шукаємо в цьому радіусі від гравця (чанки мають бути завантажені)
    public int raidVillageCount = 3;              // скільки найближчих поселень за один рейд
    public int raidMobsBase = 6;
    public double raidMobsPerDay = 0.7;
    public int raidMobsMax = 28;
    public int raidSpawnDistanceMin = 55;
    public int raidSpawnDistanceMax = 80;
    public int raidDurationSeconds = 600;         // скільки триває рейд, потім моби розходяться
    public int pastureMinAnimals = 6;             // стільки корисних тварин поруч = пасовище
    public int industryMinBlocks = 14;            // стільки блоків Create поруч = промисловий об'єкт
    public int industryBreakBudgetPerMob = 4;     // скільки блоків Create може зламати один рейдер
    public boolean raidAnnounce = true;
    /** Частка рейдерів-«прорвальників» (ідуть повз захисників до слабких цілей); діє, якщо рейдерів не менше 6. */
    public double raidBreakthroughShare = 0.35;
    public double weightPlayer = 3.0;
    public double weightVillage = 3.0;
    public double weightBarbarian = 2.0;
    public double weightPasture = 2.0;
    public double weightIndustry = 2.0;

    // --- ППО вірусу ---
    public boolean aaEnabled = true;
    public int aaTriggerRadius = 48;              // ТНТ ближче за стільки блоків до зараження = спроба підриву
    public boolean aaOnlyFromAbove = true;        // реагувати тільки на ТНТ, що вище за зараження
    public int aaCooldownSeconds = 20;
    public int aaUnits = 2;                       // база; +1 за кожні 6 днів
    public int aaMaxUnits = 6;
    public int aaGroundUnits = 4;                 // наземні початкові істоти (SCP-6101/6103/6102), що вилазять одразу
    public int aaLifetimeSeconds = 150;
    public int aaDeviceRadius = 14;               // де шукати роздатчики/поршні/слиз навколо ТНТ
    public int aaBreakPerHit = 3;                 // скільки блоків знищує за один «стрибок»

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
