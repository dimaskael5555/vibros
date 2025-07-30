package me.fixer.stalkeremission;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Random;

public final class StalkerEmission extends JavaPlugin {

    private boolean emissionActive = false;
    private final Random random = new Random();
    private BukkitTask mainEmissionTask;
    private BukkitTask warningTask;

    // --- ОБНОВЛЕННЫЕ ПЕРЕМЕННЫЕ ДЛЯ ПРОГРЕССИИ ---
    private long emissionInterval;
    private int warningDuration;
    private int emissionDuration;
    // Для урона
    private double minDamage, maxDamage;
    // Для визуальных эффектов
    private int minLightningChance, maxLightningChance;
    private int minExplosionChance, maxExplosionChance;
    private int shelterCheckHeight;

    @Override
    public void onEnable() {
        // Загружаем и сохраняем конфиг
        saveDefaultConfig();
        loadConfigValues();

        getLogger().info("Плагин StalkerEmission активирован!");

        // Запуск планировщика выбросов
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!emissionActive && !Bukkit.getOnlinePlayers().isEmpty()) {
                    startWarningPhase();
                }
            }
        }.runTaskTimer(this, emissionInterval, emissionInterval); // Используем значение из конфига
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();
        config.options().copyDefaults(true);
        saveConfig();

        emissionInterval = config.getLong("emission.interval", 30) * 60 * 20;
        warningDuration = config.getInt("emission.warning_duration", 60);
        emissionDuration = config.getInt("emission.duration", 120);
        shelterCheckHeight = config.getInt("shelter.max_check_height", 256);

        // Загружаем прогрессивный урон
        minDamage = config.getDouble("emission.progressive_damage.min", 0.5);
        maxDamage = config.getDouble("emission.progressive_damage.max", 2.0);

        // Загружаем прогрессивные шансы
        minLightningChance = config.getInt("visuals.progressive_lightning.min_chance", 5);
        maxLightningChance = config.getInt("visuals.progressive_lightning.max_chance", 20);
        minExplosionChance = config.getInt("visuals.progressive_explosion.min_chance", 2);
        maxExplosionChance = config.getInt("visuals.progressive_explosion.max_chance", 10);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("emissiontest")) {
            if (emissionActive) {
                sender.sendMessage(ChatColor.YELLOW + "Выброс уже активен!");
                return true;
            }
            sender.sendMessage(ChatColor.RED + "Запуск тестового выброса (с фазой предупреждения)...");
            startWarningPhase();
            return true;
        }

        // --- Новая команда для мгновенного выброса ---
        if (cmd.getName().equalsIgnoreCase("emissionnow")) {
            if (emissionActive) {
                sender.sendMessage(ChatColor.YELLOW + "Выброс уже активен!");
                return true;
            }
            if (warningTask != null && !warningTask.isCancelled()) {
                warningTask.cancel();
                warningTask = null;
                sender.sendMessage(ChatColor.GOLD + "Фаза предупреждения отменена. Запускаем выброс немедленно...");
            } else {
                sender.sendMessage(ChatColor.RED + "Немедленный запуск выброса для дебага...");
            }
            startEmission();
            return true;
        }
        // --- Конец новой команды ---

        if (cmd.getName().equalsIgnoreCase("emissionstop")) {
            if (emissionActive || (warningTask != null && !warningTask.isCancelled())) {
                stopEmission(true); // true - принудительная остановка
                sender.sendMessage(ChatColor.GREEN + "Выброс принудительно остановлен!");
            } else {
                sender.sendMessage(ChatColor.YELLOW + "Нет активных выбросов для остановки.");
            }
            return true;
        }
        return false;
    }

    public void startWarningPhase() {
        if (emissionActive) return;

        // Отменяем предыдущий таймер, если он был
        if (warningTask != null) warningTask.cancel();

        warningTask = new BukkitRunnable() {
            int timeLeft = warningDuration;

            @Override
            public void run() {
                if (timeLeft <= 0) {
                    startEmission();
                    this.cancel();
                    return;
                }
                
                // Оповещаем каждые 30 секунд или если осталось мало времени
                if (timeLeft % 30 == 0 || timeLeft <= 10) {
                    String title = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.warning_title"));
                    String subtitle = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.warning_subtitle"))
                            + " Осталось: " + timeLeft + "с.";

                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.sendTitle(title, subtitle, 5, 40, 10);
                        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.5f);
                    }
                    getLogger().info("До выброса осталось " + timeLeft + " секунд.");
                }
                timeLeft--;
            }
        }.runTaskTimer(this, 0L, 20L); // Каждую секунду
    }


    public void startEmission() {
        emissionActive = true;
        getLogger().info("НАЧИНАЕТСЯ ВЫБРОС!");

        String title = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.emission_start_title"));
        String subtitle = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.emission_start_subtitle"));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle(title, subtitle, 10, 70, 20);
            player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.5f);
        }

        mainEmissionTask = new BukkitRunnable() {
            int ticksLived = 0;
            final int totalDurationTicks = emissionDuration * 20;

            @Override
            public void run() {
                if (ticksLived >= totalDurationTicks) {
                    stopEmission(false);
                    return;
                }

                // --- КЛЮЧЕВОЙ МОМЕНТ: Рассчитываем прогресс от 0.0 до 1.0 ---
                double progress = (double) ticksLived / totalDurationTicks;

                Bukkit.getOnlinePlayers().forEach(player -> {
                    if (!isPlayerSafe(player)) {
                        // Передаем текущий прогресс в метод с эффектами
                        applyEmissionEffects(player, progress);
                    }
                });

                ticksLived += 20;
            }
        }.runTaskTimer(this, 0L, 20L);
    }
    
    // --- САМЫЙ ГЛАВНЫЙ ОБНОВЛЕННЫЙ МЕТОД ---
    private void applyEmissionEffects(Player player, double progress) {
        // 1. Рассчитываем текущий урон на основе прогресса
        double currentDamage = minDamage + (maxDamage - minDamage) * progress;
        player.damage(currentDamage);

        // 2. Рассчитываем текущий уровень эффектов
        FileConfiguration config = getConfig();
        for (String effectName : config.getConfigurationSection("emission.effects").getKeys(false)) {
            PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
            if (type != null) {
                int finalLevel = config.getInt("emission.effects." + effectName);
                
                // Формула для плавного повышения уровня
                // Мы делим прогресс на "шаги" для каждого уровня
                int numSteps = finalLevel + 1;
                int currentLevel = (int) (progress * numSteps);
                currentLevel = Math.min(finalLevel, currentLevel); // Убедимся, что не превысили максимум

                player.addPotionEffect(new PotionEffect(type, 60, currentLevel, true, false));
            }
        }
        
        // 3. Рассчитываем текущий шанс молний и взрывов
        double currentLightningChance = minLightningChance + (maxLightningChance - minLightningChance) * progress;
        if (random.nextDouble() * 100 < currentLightningChance) { // Используем nextDouble для точности
            Location loc = player.getLocation().add(random.nextInt(20) - 10, 0, random.nextInt(20) - 10);
            player.getWorld().strikeLightningEffect(loc);
        }

        double currentExplosionChance = minExplosionChance + (maxExplosionChance - minExplosionChance) * progress;
        if (random.nextDouble() * 100 < currentExplosionChance) {
            Location anomalyLoc = player.getLocation().add(random.nextInt(16) - 8, 0, random.nextInt(16) - 8);
            player.getWorld().createExplosion(anomalyLoc, 1.5f, false, false);
        }
    }

    /**
     * Проверяет, находится ли игрок в безопасности (под крышей).
     * @param player Игрок для проверки.
     * @return true, если игрок в безопасности.
     */
    private boolean isPlayerSafe(Player player) {
        // Игроки в креативе/спекте неуязвимы
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }
        
        // Проверяем, есть ли над головой блок, который не пропускает свет.
        // Это самый простой и надежный способ симулировать "крышу".
        Location playerLoc = player.getLocation();
        return player.getWorld().getHighestBlockYAt(playerLoc) > playerLoc.getY();
        // Можно усложнить, но для начала этого достаточно.
        // Альтернативный, более медленный вариант - луч вверх от игрока, ищем непрозрачный блок.
        // Location checkLoc = player.getLocation();
        // for(int y = checkLoc.getBlockY(); y < shelterCheckHeight; y++){
        //     if(!checkLoc.getWorld().getBlockAt(checkLoc.getBlockX(), y, checkLoc.getBlockZ()).getType().isTransparent()){
        //         return true; // Нашли крышу
        //     }
        // }
        // return false;
    }


    public void stopEmission(boolean forced) {
        if (!emissionActive && !forced) return; // Нечего останавливать, если не было принуждения

        emissionActive = false;
        
        // Отменяем все задачи
        if (warningTask != null && !warningTask.isCancelled()) {
            warningTask.cancel();
            warningTask = null;
        }
        if (mainEmissionTask != null && !mainEmissionTask.isCancelled()) {
            mainEmissionTask.cancel();
            mainEmissionTask = null;
        }
        
        getLogger().info("Выброс закончился.");

        String title = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.emission_end_title"));
        String subtitle = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.emission_end_subtitle"));
        if (forced) {
            title = ChatColor.GREEN + "Выброс остановлен";
            subtitle = ChatColor.YELLOW + "Администратор отменил выброс.";
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle(title, subtitle, 10, 70, 20);
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.5f);

            // Удаляем негативные эффекты из конфига
             for (String effectName : getConfig().getConfigurationSection("emission.effects").getKeys(false)) {
                PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
                if(type != null) player.removePotionEffect(type);
            }
        }
    }

    @Override
    public void onDisable() {
        if (emissionActive) {
            stopEmission(true);
        }
        getLogger().info("Плагин StalkerEmission деактивирован!");
    }
}
