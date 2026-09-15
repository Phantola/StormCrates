package pl.stormcrates;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class StormCratesPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private NamespacedKey crateKey, keyKey, itemKey, airdropKey;
    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, Inventory> openEnders = new HashMap<>();
    private final Map<UUID, Long> sphereCd = new HashMap<>(), dashCd = new HashMap<>(), repairCd = new HashMap<>(), slingCd = new HashMap<>(), wipeCd = new HashMap<>();
    private final Map<UUID, UUID> hookedBy = new HashMap<>();
    private final Map<UUID, Long> miniUsers = new HashMap<>();
    private final Set<String> tempBarrierBlocks = new HashSet<>();
    private final Set<String> lockedAirdrops = new HashSet<>(), claimedAirdrops = new HashSet<>();

    private static final String[] CRATES = {"rzadka","epicka","mityczna","legendarna","custom","kostiumy","budowniczego","chaosu","storm"};
    private static final String[] ITEMS = {"ender_row","elytra_hook","soul_keeper","mini","slot_wiper","sphere","loot_core","dash_buckle","repair_kit","bedrock_slingshot","builder_wand"};

    @Override public void onEnable() {
        saveDefaultConfig();
        crateKey = new NamespacedKey(this, "crate_type");
        keyKey = new NamespacedKey(this, "key_type");
        itemKey = new NamespacedKey(this, "custom_item");
        airdropKey = new NamespacedKey(this, "airdrop");
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        Objects.requireNonNull(getCommand("storm")).setExecutor(this);
        Objects.requireNonNull(getCommand("storm")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        startTickTask();
        scheduleNextAirdrop();
        getLogger().info("StormCrates 1.1.3 uruchomiony.");
    }

    @Override public void onDisable() {
        for (UUID u : new HashSet<>(miniUsers.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "attribute " + p.getName() + " minecraft:scale base set 1");
        }
        saveData();
    }

    private void saveData() {
        try { data.save(dataFile); } catch (IOException e) { getLogger().warning("Nie zapisano data.yml: " + e.getMessage()); }
    }

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void msg(CommandSender s, String text) { s.sendMessage(c("&8[&bStorm&8] &f" + text)); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (a.length == 0) { help(sender); return true; }
        if (a[0].equalsIgnoreCase("saldo")) {
            if (!(sender instanceof Player p)) return true;
            msg(p, "Saldo: &a" + money(p.getUniqueId()) + " " + getConfig().getString("waluta-symbol", "$")); return true;
        }
        if (a[0].equalsIgnoreCase("sprzedaj")) {
            if (!(sender instanceof Player p)) return true;
            sell(p, a.length >= 2 ? a[1] : "reka"); return true;
        }
        if (a[0].equalsIgnoreCase("skrzynie") && a.length >= 4 && a[1].equalsIgnoreCase("give")) {
            // accepts documented 4-arg form: /storm skrzynie give @p rzadka
            Player p = resolvePlayer(sender, a[2]); String type = a[3].toLowerCase(Locale.ROOT);
            if (!sender.hasPermission("storm.admin")) { msg(sender,"&cBrak uprawnień."); return true; }
            if (p == null || !valid(CRATES,type)) { msg(sender,"&cNieprawidłowy gracz lub typ."); return true; }
            p.getInventory().addItem(crate(type)); msg(sender,"Dano skrzynię &e"+pretty(type)+" &fgraczowi &a"+p.getName()); return true;
        }
        if (a[0].equalsIgnoreCase("klucze") && a.length >= 4 && a[1].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("storm.admin")) { msg(sender,"&cBrak uprawnień."); return true; }
            Player p = resolvePlayer(sender, a[2]); String type = a[3].toLowerCase(Locale.ROOT);
            if (p == null || !valid(CRATES,type)) { msg(sender,"&cNieprawidłowy gracz lub typ."); return true; }
            p.getInventory().addItem(key(type)); msg(sender,"Dano klucz &e"+pretty(type)+" &fgraczowi &a"+p.getName()); return true;
        }
        if (a[0].equalsIgnoreCase("klucze") && a.length >= 3 && a[1].equalsIgnoreCase("kup")) {
            if (!(sender instanceof Player p)) return true;
            String type = a[2].toLowerCase(Locale.ROOT); double price = getConfig().getDouble("ceny-kluczy."+type, -1);
            if (price < 0) { msg(p,"&cTego klucza nie można kupić."); return true; }
            if (money(p.getUniqueId()) < price) { msg(p,"&cMasz za mało pieniędzy. Cena: &e"+price); return true; }
            addMoney(p.getUniqueId(), -price); p.getInventory().addItem(key(type)); msg(p,"Kupiono klucz &e"+pretty(type)+" &fza &a"+price); return true;
        }
        if (a[0].equalsIgnoreCase("projekt") && a.length >= 2) {
            if (!(sender instanceof Player p)) return true;
            if (!isArchitect(p)) { msg(p,"&cZałóż Kostium Architekta na głowę."); return true; }
            if (a[1].equalsIgnoreCase("punkt1")) { savePoint(p,"p1"); msg(p,"&aPunkt 1 zapisany."); return true; }
            if (a[1].equalsIgnoreCase("punkt2")) { savePoint(p,"p2"); msg(p,"&aPunkt 2 zapisany."); return true; }
            if (a.length>=3 && a[1].equalsIgnoreCase("zapisz")) { saveSchematic(p,a[2]); return true; }
            if (a.length>=3 && a[1].equalsIgnoreCase("pokaz")) { showSchematic(p,a[2]); return true; }
            msg(p,"&e/storm projekt punkt1 | punkt2 | zapisz <nazwa> | pokaz <nazwa>"); return true;
        }
        if (a[0].equalsIgnoreCase("profil")) {
            Player target = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : (sender instanceof Player p ? p : null);
            if (target == null) { msg(sender,"&cNie znaleziono gracza online."); return true; }
            showProfile(sender,target); return true;
        }
        if (a[0].equalsIgnoreCase("osiagniecia") || a[0].equalsIgnoreCase("osiągnięcia")) {
            if (!(sender instanceof Player p)) return true; showAchievements(p); return true;
        }
        if (a[0].equalsIgnoreCase("airdrop") && a.length>=2 && a[1].equalsIgnoreCase("start")) {
            if (!sender.hasPermission("storm.admin")) { msg(sender,"&cBrak uprawnień."); return true; }
            spawnAirdrop(); return true;
        }
        if (a[0].equalsIgnoreCase("item") && a.length >= 4 && a[1].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("storm.admin")) { msg(sender,"&cBrak uprawnień."); return true; }
            Player p=resolvePlayer(sender,a[2]); String id=a[3].toLowerCase(Locale.ROOT);
            if (p==null || !valid(ITEMS,id)) { msg(sender,"&cNieprawidłowy gracz lub przedmiot."); return true; }
            p.getInventory().addItem(customItem(id)); msg(sender,"Dano &d"+prettyItem(id)+" &fgraczowi &a"+p.getName()); return true;
        }
        help(sender); return true;
    }

    private void help(CommandSender s) {
        msg(s,"&e/storm saldo &7- sprawdź pieniądze");
        msg(s,"&e/storm sprzedaj reka|wszystko &7- sprzedaj przedmioty");
        msg(s,"&e/storm klucze kup <typ> &7- kup klucz");
        msg(s,"&e/storm projekt punkt1|punkt2|zapisz|pokaz &7- Kostium Architekta");
        msg(s,"&e/storm profil [gracz] &7- profil Storm");
        msg(s,"&e/storm osiagniecia &7- osiągnięcia Storm");
        if (s.hasPermission("storm.admin")) {
            msg(s,"&cADMIN: /storm skrzynie give <gracz/@p> <typ>");
            msg(s,"&cADMIN: /storm klucze give <gracz/@p> <typ>");
            msg(s,"&cADMIN: /storm item give <gracz/@p> <id>");
            msg(s,"&cADMIN: /storm airdrop start");
        }
    }

    private Player resolvePlayer(CommandSender sender, String s) {
        if (s.equalsIgnoreCase("@p") && sender instanceof Player p) return p;
        if (s.equalsIgnoreCase("@p")) return Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        return Bukkit.getPlayerExact(s);
    }

    private boolean valid(String[] arr,String s){ return Arrays.asList(arr).contains(s); }
    private String pretty(String t){ return switch(t){case"rzadka"->"Rzadka";case"epicka"->"Epicka";case"mityczna"->"Mityczna";case"legendarna"->"Legendarna";case"custom"->"Niestandardowa";case"kostiumy"->"Kostiumów";case"budowniczego"->"Budowniczego";case"chaosu"->"Chaosu";default->"Storm";};}
    private String prettyItem(String id){ return switch(id){case"ender_row"->"Powiększacz Enderu";case"elytra_hook"->"Hak Anty-Elytra";case"soul_keeper"->"Strażnik Duszy";case"mini"->"Totem Miniatury";case"slot_wiper"->"Tasowarka Slotów";case"sphere"->"Sfera Absolutna";case"loot_core"->"Rdzeń Łupu";case"dash_buckle"->"Klamra Burzy";case"repair_kit"->"Impuls Naprawczy";case"bedrock_slingshot"->"Proca Bedrocku";default->"Różdżka Budowniczego";};}

    private ItemStack crate(String type) {
        ItemStack it=new ItemStack(Material.CHEST); ItemMeta m=it.getItemMeta();
        m.setDisplayName(c("&6✦ Skrzynia " + pretty(type) + " ✦")); m.setLore(List.of(c("&7Postaw ją, a następnie użyj"),c("&7klucza: &e"+pretty(type)),c("&8Nie można jej zniszczyć w trybie przetrwania.")));
        m.getPersistentDataContainer().set(crateKey,PersistentDataType.STRING,type); it.setItemMeta(m); return it;
    }
    private ItemStack key(String type) {
        ItemStack it=new ItemStack(Material.TRIPWIRE_HOOK); ItemMeta m=it.getItemMeta();
        m.setDisplayName(c("&e⚿ Klucz " + pretty(type))); m.setLore(List.of(c("&7Otwiera skrzynię: &f"+pretty(type))));
        m.getPersistentDataContainer().set(keyKey,PersistentDataType.STRING,type); it.setItemMeta(m); return it;
    }

    private ItemStack customItem(String id) {
        Material mat=switch(id){case"ender_row"->Material.ENDER_EYE;case"elytra_hook"->Material.FISHING_ROD;case"soul_keeper"->Material.TOTEM_OF_UNDYING;case"mini"->Material.AMETHYST_SHARD;case"slot_wiper"->Material.NETHERITE_SWORD;case"sphere"->Material.HEART_OF_THE_SEA;case"loot_core"->Material.VAULT;case"dash_buckle"->Material.RABBIT_FOOT;case"repair_kit"->Material.EXPERIENCE_BOTTLE;case"bedrock_slingshot"->Material.FIREWORK_STAR;default->Material.BLAZE_ROD;};
        ItemStack it=new ItemStack(mat); ItemMeta m=it.getItemMeta(); m.setDisplayName(c("&d✦ "+prettyItem(id)+" ✦"));
        List<String> lore=new ArrayList<>();
        switch(id){
            case"ender_row"->lore=List.of(c("&7Dodaje +9 miejsc do Skrzyni Endu."),c("&7Maksymalnie 3 użycia na gracza."),c("&ePPM, aby użyć."));
            case"elytra_hook"->lore=List.of(c("&7Złów gracza. Nie może latać Elytrą"),c("&7dopóki nie oddali się o 30 bloków."));
            case"soul_keeper"->lore=List.of(c("&7Trzymaj w drugiej ręce."),c("&7Po śmierci zachowujesz wszystko poza tym itemem."));
            case"mini"->lore=List.of(c("&7Trzymaj w głównej ręce, aby zmniejszyć się"),c("&7do około połowy bloku."));
            case"slot_wiper"->lore=List.of(c("&7Trafienie losowo miesza przedmioty w ekwipunku przeciwnika."),c("&7Nie usuwa żadnych przedmiotów. Zbroja i off-hand bez zmian."),c("&7Czas odnowienia: 30 s."));
            case"sphere"->lore=List.of(c("&7Tworzy niezniszczalną sferę na 20 s."),c("&7Czas odnowienia: 2 min."),c("&ePPM, aby użyć."));
            case"loot_core"->lore=List.of(c("&7Miej go w ekwipunku podczas zabójstwa."),c("&7Przedmioty pokonanego trafiają do rdzenia."),c("&ePPM, aby odebrać łup."));
            case"dash_buckle"->lore=List.of(c("&7Szybki skok w kierunku patrzenia."),c("&7Czas odnowienia: 15 s."),c("&ePPM, aby użyć."));
            case"repair_kit"->lore=List.of(c("&7Naprawia jeden uszkodzony element zbroi."),c("&7Jednorazowy. Czas odnowienia: 10 s."),c("&ePPM, aby użyć."));
            case"bedrock_slingshot"->lore=List.of(c("&7PPM na skale macierzystej = niszczy blok."),c("&7Czas odnowienia: 700 s."));
            case"builder_wand"->lore=List.of(c("&7PPM na bloku: przedłuża linię tym samym blokiem."),c("&7Zużywa bloki z ekwipunku. Maks. 5 bloków."),c("&7Z Kostiumem Architekta: maks. 8 bloków."));
        }
        m.setLore(lore); m.getPersistentDataContainer().set(itemKey,PersistentDataType.STRING,id); it.setItemMeta(m); return it;
    }

    private String id(ItemStack it, NamespacedKey k){ if(it==null||!it.hasItemMeta())return null; return it.getItemMeta().getPersistentDataContainer().get(k,PersistentDataType.STRING); }

    @EventHandler public void onPlace(BlockPlaceEvent e){
        String type=id(e.getItemInHand(),crateKey); if(type==null)return;
        BlockState st=e.getBlockPlaced().getState(); if(st instanceof TileState ts){ts.getPersistentDataContainer().set(crateKey,PersistentDataType.STRING,type);ts.update(true); e.getPlayer().sendMessage(c("&aPostawiono skrzynię &e"+pretty(type)+"&a."));}
    }
    @EventHandler public void onBreak(BlockBreakEvent e){
        String loc=locKey(e.getBlock()); if(tempBarrierBlocks.contains(loc)){e.setCancelled(true);return;}
        BlockState st=e.getBlock().getState(); if(st instanceof TileState ts && ts.getPersistentDataContainer().has(crateKey,PersistentDataType.STRING) && e.getPlayer().getGameMode()==GameMode.SURVIVAL){e.setCancelled(true);msg(e.getPlayer(),"&cSkrzyni Storm nie można zniszczyć w trybie przetrwania.");}
    }
    @EventHandler public void onCrateUse(PlayerInteractEvent e){
        if(e.getHand()!=EquipmentSlot.HAND)return;
        if(e.getAction()!=Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null)return;
        BlockState st=e.getClickedBlock().getState(); if(!(st instanceof TileState ts))return;
        String crate=ts.getPersistentDataContainer().get(crateKey,PersistentDataType.STRING); if(crate==null)return;
        e.setCancelled(true); Player p=e.getPlayer(); String key=id(p.getInventory().getItemInMainHand(),keyKey);
        if(key==null || !key.equals(crate)){msg(p,"&cPotrzebujesz klucza: &e"+pretty(crate));return;}
        consumeMain(p,1); incStat(p,"crates",1); checkAchievements(p); ItemStack prize=rollPrize(crate,p); if(prize!=null){p.getInventory().addItem(prize).values().forEach(x->p.getWorld().dropItemNaturally(p.getLocation(),x));}
        p.getWorld().playSound(p.getLocation(),Sound.BLOCK_ENDER_CHEST_OPEN,1f,1.15f); p.spawnParticle(Particle.FIREWORK,p.getLocation().add(0,1,0),25,0.6,0.7,0.6,0.05);
        msg(p,"Otworzyłeś skrzynię &e"+pretty(crate)+"&f!");
    }

    private ItemStack rollPrize(String t, Player p){
        ThreadLocalRandom r=ThreadLocalRandom.current();
        // 0,001% = 1 na 100 000 otwarć Epickiej Skrzyni.
        if(t.equals("epicka") && r.nextInt(100000)==0){
            ItemStack b=new ItemStack(Material.BEDROCK); named(b,"&4✦ BEDROCK 0,001% ✦");
            Bukkit.broadcastMessage(c("&6[Storm] &e"+p.getName()+" &ftrafił &4BEDROCK &fz Epickiej Skrzyni!")); return b;
        }
        if(t.equals("rzadka")) return rarePrize(r.nextInt(100));
        if(t.equals("epicka")) return epicPrize(r.nextInt(100));
        if(t.equals("mityczna")) return mythicPrize(r.nextInt(100));
        if(t.equals("legendarna")) return legendaryPrize(r.nextInt(100));
        if(t.equals("custom")) return customPrize(r.nextInt(100));
        if(t.equals("kostiumy")) return costumePrize(r.nextInt(100));
        if(t.equals("budowniczego")) return builderPrize(r.nextInt(100));
        if(t.equals("chaosu")) return chaosPrize(r.nextInt(100),p);
        return stormPrize(r.nextInt(100));
    }
    private ItemStack rarePrize(int x){
        if(x<18)return new ItemStack(Material.GOLDEN_APPLE,2);
        Material m=x<35?Material.DIAMOND_SWORD:x<52?Material.DIAMOND_PICKAXE:x<68?Material.DIAMOND_AXE:x<84?Material.DIAMOND_CHESTPLATE:Material.DIAMOND_BOOTS;
        ItemStack it=new ItemStack(m); enchantBasic(it,3); named(it,"&bRzadki: &f"+prettyMat(m)); return it;
    }
    private ItemStack epicPrize(int x){
        if(x<8)return new ItemStack(Material.GOLDEN_APPLE,5);
        if(x<11)return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE,1);
        if(x<18)return new ItemStack(Material.ENDER_PEARL,8);
        Material m=x<34?Material.DIAMOND_SWORD:x<50?Material.DIAMOND_PICKAXE:x<67?Material.DIAMOND_CHESTPLATE:x<84?Material.DIAMOND_LEGGINGS:Material.DIAMOND_HELMET;
        ItemStack it=new ItemStack(m); enchantBasic(it,4); named(it,"&5Epicki: &f"+prettyMat(m)); return it;
    }
    private ItemStack mythicPrize(int x){
        if(x<10)return new ItemStack(Material.GOLDEN_APPLE,8);
        if(x<15)return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE,1);
        if(x<20)return new ItemStack(Material.TOTEM_OF_UNDYING,1);
        if(x<28)return new ItemStack(Material.NETHERITE_INGOT,2);
        Material m=x<43?Material.NETHERITE_SWORD:x<57?Material.NETHERITE_PICKAXE:x<70?Material.NETHERITE_CHESTPLATE:x<82?Material.NETHERITE_LEGGINGS:x<91?Material.NETHERITE_HELMET:Material.NETHERITE_BOOTS;
        ItemStack it=new ItemStack(m); enchantBasic(it,5); named(it,"&dMityczny: &f"+prettyMat(m)); return it;
    }
    private ItemStack legendaryPrize(int x){
        if(x<10)return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE,2);
        if(x<18)return new ItemStack(Material.TOTEM_OF_UNDYING,2);
        if(x<28)return new ItemStack(Material.NETHERITE_INGOT,6);
        if(x<33){ItemStack e=new ItemStack(Material.ELYTRA);e.addUnsafeEnchantment(Enchantment.UNBREAKING,3);named(e,"&6Legendarna Elytra");return e;}
        Material m=x<50?Material.NETHERITE_SWORD:x<65?Material.NETHERITE_PICKAXE:x<80?Material.NETHERITE_CHESTPLATE:x<90?Material.NETHERITE_LEGGINGS:Material.NETHERITE_AXE;
        ItemStack it=new ItemStack(m); enchantBasic(it,6); named(it,"&6Legendarny: &f"+prettyMat(m)); return it;
    }
    private ItemStack customPrize(int x){
        // Słabsze itemy wypadają częściej, najmocniejsze są wyraźnie rzadsze.
        String id=x<18?"repair_kit":x<34?"dash_buckle":x<48?"ender_row":x<60?"mini":x<71?"slot_wiper":x<81?"elytra_hook":x<89?"soul_keeper":x<95?"sphere":x<99?"loot_core":"bedrock_slingshot";
        return customItem(id);
    }

    private ItemStack costumePrize(int x){
        if(x<28)return costumePiece("gornik", Material.NETHERITE_HELMET, "&6⛏ Hełm Górnika", "&7Część Kostiumu Górnika.");
        if(x<48)return costumePiece("tank", Material.NETHERITE_CHESTPLATE, "&c🛡 Pancerz Tanka", "&7Część Kostiumu Tanka.");
        if(x<66)return costumePiece("speed", Material.LEATHER_BOOTS, "&b⚡ Buty Biegacza", "&7Część Kostiumu Szybkości.");
        if(x<82)return costumePiece("assassin", Material.LEATHER_CHESTPLATE, "&5✦ Płaszcz Zabójcy", "&7Część Kostiumu Zabójcy.");
        return architectHelmet();
    }
    private ItemStack costumePiece(String costume, Material mat, String name, String lore){
        ItemStack it=new ItemStack(mat); ItemMeta m=it.getItemMeta(); m.setDisplayName(c(name));
        m.setLore(List.of(c(lore),c("&8Przedmiot ze Skrzyni Kostiumów.")));
        m.getPersistentDataContainer().set(itemKey,PersistentDataType.STRING,"costume_"+costume); it.setItemMeta(m); return it;
    }
    private ItemStack architectHelmet(){
        ItemStack it=costumePiece("architect",Material.CARVED_PUMPKIN,"&a⌂ Kostium Architekta","&7Tryb budowania inspirowany Litematica.");
        ItemMeta m=it.getItemMeta(); m.setLore(List.of(c("&7Załóż na głowę, aby aktywować tryb Architekta."),c("&e/storm projekt zapisz <nazwa> &7- zapamiętaj obszar"),c("&e/storm projekt pokaz <nazwa> &7- pokaż ghost-build"),c("&7Podgląd używa cząsteczek i nie stawia bloków automatycznie.")));it.setItemMeta(m);return it;
    }


    private ItemStack builderPrize(int x){
        if(x<15)return new ItemStack(Material.QUARTZ_BLOCK,64);
        if(x<30)return new ItemStack(Material.DEEPSLATE_BRICKS,64);
        if(x<45){Material[] c={Material.WHITE_CONCRETE,Material.BLACK_CONCRETE,Material.RED_CONCRETE,Material.BLUE_CONCRETE,Material.GREEN_CONCRETE,Material.YELLOW_CONCRETE};return new ItemStack(c[ThreadLocalRandom.current().nextInt(c.length)],64);}
        if(x<55)return new ItemStack(Material.SEA_LANTERN,32);
        if(x<65)return new ItemStack(Material.GLASS,32);
        if(x<73)return new ItemStack(Material.OBSIDIAN,32);
        if(x<80)return new ItemStack(Material.END_ROD,16);
        if(x<87)return new ItemStack(Material.SHULKER_BOX,1);
        if(x<92)return new ItemStack(Material.IRON_BLOCK,16);
        if(x<94)return new ItemStack(Material.DIAMOND_BLOCK,8);
        if(x<95)return new ItemStack(Material.BEACON,1);
        return customItem("builder_wand");
    }

    private ItemStack chaosPrize(int x, Player p){
        if(x>=98){
            ItemStack a=chaosPrize(ThreadLocalRandom.current().nextInt(0,98),p);
            ItemStack b=chaosPrize(ThreadLocalRandom.current().nextInt(0,98),p);
            p.getInventory().addItem(a,b).values().forEach(it->p.getWorld().dropItemNaturally(p.getLocation(),it));
            incStat(p,"jackpots",1); checkAchievements(p); Bukkit.broadcastMessage(c("&6[Storm] &e"+p.getName()+" &ftrafił &d⚡ JACKPOT CHAOSU ⚡ &fi dostał 3 nagrody!"));
            return chaosPrize(ThreadLocalRandom.current().nextInt(0,98),p);
        }
        if(x<12)return new ItemStack(Material.DIRT,64);
        if(x<21)return new ItemStack(Material.POTATO,64);
        if(x<31)return new ItemStack(Material.TNT,32);
        if(x<41)return new ItemStack(Material.ENDER_PEARL,16);
        if(x<49)return new ItemStack(Material.GOLDEN_APPLE,16);
        if(x<57)return new ItemStack(Material.DIAMOND,8);
        if(x<63)return new ItemStack(Material.NETHERITE_INGOT,3);
        if(x<69)return new ItemStack(Material.TOTEM_OF_UNDYING,1);
        if(x<74)return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE,1);
        if(x<79)return customPrize(ThreadLocalRandom.current().nextInt(100));
        if(x<84)return costumePrize(ThreadLocalRandom.current().nextInt(100));
        if(x<89){ItemStack sh=new ItemStack(Material.SHULKER_BOX);named(sh,"&dSkrzynia Niespodzianka Chaosu");return sh;}
        if(x<93)return new ItemStack(Material.DIAMOND_BLOCK,1);
        if(x<96)return new ItemStack(Material.NETHERITE_BLOCK,1);
        ItemStack potato=new ItemStack(Material.POTATO);ItemMeta m=potato.getItemMeta();m.setDisplayName(c("&6✦ Ziemniak Chaosu ✦"));m.setLore(List.of(c("&7Najrzadszy bezużyteczny przedmiot na serwerze.")));potato.setItemMeta(m);return potato;
    }

    private ItemStack stormPrize(int x){
        if(x<18)return new ItemStack(Material.NETHERITE_INGOT,16);
        if(x<33)return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE,8);
        if(x<45)return new ItemStack(Material.TOTEM_OF_UNDYING,4);
        if(x<55){ItemStack e=new ItemStack(Material.ELYTRA);e.addUnsafeEnchantment(Enchantment.UNBREAKING,5);e.addUnsafeEnchantment(Enchantment.MENDING,1);named(e,"&b⚡ ELYTRA STORM ⚡");return e;}
        if(x<70)return customPrize(ThreadLocalRandom.current().nextInt(70,100));
        if(x<82)return new ItemStack(Material.NETHERITE_BLOCK,2);
        if(x<90){ItemStack m=new ItemStack(Material.MACE);enchantBasic(m,7);named(m,"&b⚡ MŁOT STORM ⚡");return m;}
        ItemStack it=new ItemStack(Material.NETHERITE_SWORD); enchantBasic(it,7); named(it,"&b⚡ OSTRZE STORM ⚡"); return it;
    }
    private void named(ItemStack it,String name){ItemMeta m=it.getItemMeta();m.setDisplayName(c(name));it.setItemMeta(m);}
    private String prettyMat(Material m){return m.name().toLowerCase(Locale.ROOT).replace('_',' ');}
    private void enchantBasic(ItemStack it,int lvl){
        if(it.getType().name().contains("SWORD")||it.getType().name().contains("AXE")){it.addUnsafeEnchantment(Enchantment.SHARPNESS,lvl);it.addUnsafeEnchantment(Enchantment.UNBREAKING,Math.max(2,lvl/2));}
        else if(it.getType().name().contains("PICKAXE")){it.addUnsafeEnchantment(Enchantment.EFFICIENCY,lvl);it.addUnsafeEnchantment(Enchantment.UNBREAKING,Math.max(2,lvl/2));}
        else {it.addUnsafeEnchantment(Enchantment.PROTECTION,lvl);it.addUnsafeEnchantment(Enchantment.UNBREAKING,Math.max(2,lvl/2));}
    }

    @EventHandler public void customUse(PlayerInteractEvent e){
        if(e.getHand()!=EquipmentSlot.HAND)return; ItemStack it=e.getPlayer().getInventory().getItemInMainHand(); String id=id(it,itemKey); if(id==null)return;
        Player p=e.getPlayer();
        if(id.equals("ender_row") && (e.getAction()==Action.RIGHT_CLICK_AIR||e.getAction()==Action.RIGHT_CLICK_BLOCK)){e.setCancelled(true);int u=data.getInt("ender-upgrades."+p.getUniqueId(),0);if(u>=3){msg(p,"&cMasz już maksymalne 3 ulepszenia.");return;}data.set("ender-upgrades."+p.getUniqueId(),u+1);saveData();consumeMain(p,1);msg(p,"&aEnder Chest powiększony o 9 slotów. Poziom: "+(u+1)+"/3");}
        else if(id.equals("sphere") && isRight(e)){e.setCancelled(true);if(!ready(sphereCd,p,getConfig().getInt("custom.sfera-cooldown-sekundy",120),"Sfera"))return;createSphere(p);}
        else if(id.equals("dash_buckle") && isRight(e)){e.setCancelled(true);if(!ready(dashCd,p,getConfig().getInt("custom.dash-cooldown-sekundy",15),"Dash"))return;Vector v=p.getLocation().getDirection().normalize().multiply(2.2);v.setY(Math.max(0.35,v.getY()));p.setVelocity(v);p.getWorld().playSound(p.getLocation(),Sound.ENTITY_ENDER_DRAGON_FLAP,1f,1f);}
        else if(id.equals("repair_kit") && isRight(e)){e.setCancelled(true);if(!hasDamagedArmor(p)){msg(p,"&eNie masz uszkodzonej zbroi.");return;}if(!ready(repairCd,p,getConfig().getInt("custom.naprawa-cooldown-sekundy",10),"Naprawa"))return;repairArmor(p);}
        else if(id.equals("loot_core") && isRight(e)){e.setCancelled(true);claimLootCore(p);}
        else if(id.equals("builder_wand") && e.getAction()==Action.RIGHT_CLICK_BLOCK && e.getClickedBlock()!=null){e.setCancelled(true);useBuilderWand(p,e.getClickedBlock(),e.getBlockFace());}
    }

    private void useBuilderWand(Player p, Block clicked, org.bukkit.block.BlockFace face){
        Material mat=clicked.getType();
        if(!mat.isBlock() || mat.isAir()){msg(p,"&cTen blok nie nadaje się do Różdżki.");return;}
        int max=isArchitect(p)?8:5, placed=0;
        for(int i=1;i<=max;i++){
            Block target=clicked.getRelative(face,i);
            if(!target.getType().isAir())break;
            int slot=findMaterial(p,mat);
            if(slot<0)break;
            ItemStack stack=p.getInventory().getItem(slot);
            target.setType(mat,false);
            stack.setAmount(stack.getAmount()-1);
            p.getInventory().setItem(slot,stack.getAmount()<=0?null:stack);
            placed++;
        }
        if(placed==0)msg(p,"&eBrak miejsca albo bloków w ekwipunku."); else msg(p,"&aRóżdżka postawiła &e"+placed+" &abloków.");
    }
    private int findMaterial(Player p,Material mat){for(int i=0;i<36;i++){ItemStack x=p.getInventory().getItem(i);if(x!=null&&x.getType()==mat&&x.getAmount()>0)return i;}return -1;}

    private boolean isRight(PlayerInteractEvent e){return e.getAction()==Action.RIGHT_CLICK_AIR||e.getAction()==Action.RIGHT_CLICK_BLOCK;}
    private boolean ready(Map<UUID,Long> map,Player p,int seconds,String what){long now=System.currentTimeMillis();long end=map.getOrDefault(p.getUniqueId(),0L);if(end>now){msg(p,"&c"+what+" za "+((end-now+999)/1000)+" s.");return false;}map.put(p.getUniqueId(),now+seconds*1000L);return true;}

    private boolean hasDamagedArmor(Player p){for(ItemStack a:p.getInventory().getArmorContents())if(a!=null&&a.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d&&d.hasDamage())return true;return false;}

    private void repairArmor(Player p){
        for(ItemStack a:p.getInventory().getArmorContents()) if(a!=null && a.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d && d.hasDamage()){int repair=Math.max(1,a.getType().getMaxDurability()/2);d.setDamage(Math.max(0,d.getDamage()-repair));a.setItemMeta(d);consumeMain(p,1);msg(p,"&aNaprawiono część zbroi.");return;}msg(p,"&eNie masz uszkodzonej zbroi.");
    }
    private void consumeMain(Player p,int amount){ItemStack h=p.getInventory().getItemInMainHand();h.setAmount(h.getAmount()-amount);p.getInventory().setItemInMainHand(h.getAmount()<=0?null:h);}

    @EventHandler public void onFish(PlayerFishEvent e){if(!(e.getCaught() instanceof Player victim))return;String id=id(e.getPlayer().getInventory().getItemInMainHand(),itemKey);if(!"elytra_hook".equals(id))return;hookedBy.put(victim.getUniqueId(),e.getPlayer().getUniqueId());msg(e.getPlayer(),"&aZablokowano Elytrę gracza &e"+victim.getName());msg(victim,"&cTwoja Elytra jest zablokowana do dystansu 30 bloków!");}

    @EventHandler public void onHit(EntityDamageByEntityEvent e){if(e.isCancelled()||!(e.getDamager() instanceof Player p)||!(e.getEntity() instanceof Player v))return;String id=id(p.getInventory().getItemInMainHand(),itemKey);if(!"slot_wiper".equals(id))return;boolean hasItems=false;for(int i=0;i<36;i++){ItemStack x=v.getInventory().getItem(i);if(x!=null&&!x.getType().isAir()){hasItems=true;break;}}if(!hasItems){msg(p,"&ePrzeciwnik nie ma przedmiotów do przetasowania.");return;}if(!ready(wipeCd,p,getConfig().getInt("custom.kasowanie-slotu-cooldown-sekundy",30),"Tasowarka Slotów"))return;List<ItemStack> contents=new ArrayList<>();for(int i=0;i<36;i++){ItemStack x=v.getInventory().getItem(i);if(x!=null&&!x.getType().isAir())contents.add(x.clone());v.getInventory().setItem(i,null);}Collections.shuffle(contents);List<Integer> slots=new ArrayList<>();for(int i=0;i<36;i++)slots.add(i);Collections.shuffle(slots);for(int i=0;i<contents.size();i++)v.getInventory().setItem(slots.get(i),contents.get(i));msg(p,"&aPrzetasowano sloty gracza "+v.getName()+". Żaden przedmiot nie został usunięty.");msg(v,"&cTasowarka Slotów przetasowała twój ekwipunek!");}

    @EventHandler(priority=EventPriority.HIGHEST) public void onDeath(PlayerDeathEvent e){
        Player p=e.getPlayer();
        incStat(p,"deaths",1);
        Player statKiller=p.getKiller();
        if(statKiller!=null && !statKiller.getUniqueId().equals(p.getUniqueId())){incStat(statKiller,"kills",1);checkAchievements(statKiller);}
        ItemStack off=p.getInventory().getItemInOffHand(); if("soul_keeper".equals(id(off,itemKey))){e.setKeepInventory(true);e.getDrops().clear();e.setKeepLevel(true);e.setDroppedExp(0);off.setAmount(off.getAmount()-1);p.getInventory().setItemInOffHand(off.getAmount()<=0?null:off);msg(p,"&aStrażnik Duszy ocalił twój ekwipunek.");return;}
        Player killer=p.getKiller(); if(killer!=null && hasCustom(killer,"loot_core")){List<ItemStack> drops=new ArrayList<>();for(ItemStack x:e.getDrops())drops.add(x.clone());e.getDrops().clear();String path="loot-core."+killer.getUniqueId();List<ItemStack> old=(List<ItemStack>)(List<?>)data.getList(path,new ArrayList<>());old.addAll(drops);data.set(path,old);saveData();msg(killer,"&aŁup gracza &e"+p.getName()+" &atrafił do Rdzenia Łupu.");}
    }
    private boolean hasCustom(Player p,String wanted){for(ItemStack x:p.getInventory().getContents())if(wanted.equals(id(x,itemKey)))return true;return false;}
    private void claimLootCore(Player p){String path="loot-core."+p.getUniqueId();List<?> raw=data.getList(path,new ArrayList<>());if(raw.isEmpty()){msg(p,"&eRdzeń jest pusty.");return;}int count=0;for(Object o:raw)if(o instanceof ItemStack it){p.getInventory().addItem(it).values().forEach(x->p.getWorld().dropItemNaturally(p.getLocation(),x));count+=it.getAmount();}data.set(path,null);saveData();msg(p,"&aOdebrano łup. Przedmioty: "+count);}

    private void createSphere(Player p){
        Location c=p.getLocation().getBlock().getLocation();int r=4;Map<String,Material> old=new HashMap<>();
        for(int x=-r;x<=r;x++)for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++){double d=Math.sqrt(x*x+y*y+z*z);if(d<r-0.55||d>r+0.55)continue;Block b=c.clone().add(x,y,z).getBlock();if(!b.getType().isAir())continue;String k=locKey(b);old.put(k,b.getType());tempBarrierBlocks.add(k);b.setType(Material.BARRIER,false);}
        msg(p,"&bSfera aktywna przez "+getConfig().getLong("custom.sfera-czas-sekundy",20)+" sekund.");
        new BukkitRunnable(){public void run(){for(String k:old.keySet()){Block b=blockFromKey(k);if(b!=null&&b.getType()==Material.BARRIER)b.setType(old.get(k),false);tempBarrierBlocks.remove(k);}msg(p,"&7Sfera zniknęła.");}}.runTaskLater(this,getConfig().getLong("custom.sfera-czas-sekundy",20)*20L);
    }
    private String locKey(Block b){return b.getWorld().getName()+":"+b.getX()+":"+b.getY()+":"+b.getZ();}
    private Block blockFromKey(String k){String[] s=k.split(":");World w=Bukkit.getWorld(s[0]);return w==null?null:w.getBlockAt(Integer.parseInt(s[1]),Integer.parseInt(s[2]),Integer.parseInt(s[3]));}

    @EventHandler public void onQuit(PlayerQuitEvent e){
        Player p=e.getPlayer();
        Inventory inv=openEnders.remove(p.getUniqueId());
        if(inv!=null){for(int i=0;i<27;i++)p.getEnderChest().setItem(i,inv.getItem(i));List<ItemStack> extra=new ArrayList<>();for(int i=27;i<inv.getSize();i++)extra.add(inv.getItem(i));data.set("ender-extra."+p.getUniqueId(),extra);saveData();}
        hookedBy.remove(p.getUniqueId());
        hookedBy.values().removeIf(id -> id.equals(p.getUniqueId()));
        if(miniUsers.remove(p.getUniqueId())!=null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"attribute "+p.getName()+" minecraft:scale base set 1");
    }

    @EventHandler public void onEntityExplode(EntityExplodeEvent e){e.blockList().removeIf(this::isProtectedStormBlock);}
    @EventHandler public void onBlockExplode(BlockExplodeEvent e){e.blockList().removeIf(this::isProtectedStormBlock);}
    private boolean isProtectedStormBlock(Block b){
        if(tempBarrierBlocks.contains(locKey(b))) return true;
        BlockState st=b.getState();
        return st instanceof TileState ts && ts.getPersistentDataContainer().has(crateKey,PersistentDataType.STRING);
    }

    @EventHandler public void onEnderOpen(InventoryOpenEvent e){if(!(e.getPlayer() instanceof Player p)||e.getInventory().getType()!=InventoryType.ENDER_CHEST)return;int u=data.getInt("ender-upgrades."+p.getUniqueId(),0);if(u<=0)return;e.setCancelled(true);int size=27+9*u;Inventory inv=Bukkit.createInventory(null,size,c("&5Ender Chest+ &7("+u+"/3)"));for(int i=0;i<27;i++)inv.setItem(i,p.getEnderChest().getItem(i));List<?> extra=data.getList("ender-extra."+p.getUniqueId(),new ArrayList<>());for(int i=0;i<extra.size()&&27+i<size;i++)if(extra.get(i) instanceof ItemStack it)inv.setItem(27+i,it);openEnders.put(p.getUniqueId(),inv);Bukkit.getScheduler().runTask(this,()->p.openInventory(inv));}
    @EventHandler public void onEnderClose(InventoryCloseEvent e){if(!(e.getPlayer() instanceof Player p))return;Inventory inv=openEnders.get(p.getUniqueId());if(inv==null||e.getInventory()!=inv)return;for(int i=0;i<27;i++)p.getEnderChest().setItem(i,inv.getItem(i));List<ItemStack> extra=new ArrayList<>();for(int i=27;i<inv.getSize();i++)extra.add(inv.getItem(i));data.set("ender-extra."+p.getUniqueId(),extra);saveData();openEnders.remove(p.getUniqueId());}


    private boolean isArchitect(Player p){ return "costume_architect".equals(id(p.getInventory().getHelmet(),itemKey)); }
    private void savePoint(Player p,String which){ Location l=p.getLocation().getBlock().getLocation();data.set("projects-points."+p.getUniqueId()+"."+which,List.of(l.getBlockX(),l.getBlockY(),l.getBlockZ()));data.set("projects-points."+p.getUniqueId()+".world",l.getWorld().getName());saveData(); }
    private void saveSchematic(Player p,String name){
        String base="projects-points."+p.getUniqueId();List<Integer>a=data.getIntegerList(base+".p1"),b=data.getIntegerList(base+".p2");String wn=data.getString(base+".world");
        if(a.size()<3||b.size()<3||wn==null){msg(p,"&cNajpierw ustaw punkt1 i punkt2.");return;} World w=Bukkit.getWorld(wn);if(w==null||p.getWorld()!=w){msg(p,"&cPunkty muszą być w tym świecie.");return;}
        int minX=Math.min(a.get(0),b.get(0)),minY=Math.min(a.get(1),b.get(1)),minZ=Math.min(a.get(2),b.get(2)),maxX=Math.max(a.get(0),b.get(0)),maxY=Math.max(a.get(1),b.get(1)),maxZ=Math.max(a.get(2),b.get(2));long vol=(long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1);if(vol>4096){msg(p,"&cProjekt jest za duży. Maksymalnie 4096 bloków.");return;}
        List<String> blocks=new ArrayList<>();for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++){Material m=w.getBlockAt(x,y,z).getType();if(!m.isAir())blocks.add((x-minX)+","+(y-minY)+","+(z-minZ)+","+m.name());}
        String path="projects."+p.getUniqueId()+"."+name.toLowerCase(Locale.ROOT);data.set(path+".blocks",blocks);data.set(path+".size",List.of(maxX-minX+1,maxY-minY+1,maxZ-minZ+1));saveData();msg(p,"&aProjekt &e"+name+" &azapisany: "+blocks.size()+" bloków.");
    }
    private void showSchematic(Player p,String name){
        List<String> blocks=data.getStringList("projects."+p.getUniqueId()+"."+name.toLowerCase(Locale.ROOT)+".blocks");if(blocks.isEmpty()){msg(p,"&cNie znaleziono projektu.");return;}Location o=p.getLocation().getBlock().getLocation();int shown=0;for(String row:blocks){String[]q=row.split(",",4);if(q.length<4)continue;Location l=o.clone().add(Integer.parseInt(q[0])+0.5,Integer.parseInt(q[1])+0.5,Integer.parseInt(q[2])+0.5);if(shown++<1200)p.spawnParticle(Particle.END_ROD,l,1,0,0,0,0);}
        msg(p,"&aPodgląd projektu &e"+name+"&a pokazany od Twojej pozycji. To podgląd cząsteczkowy, nie pełna Litematica klientowa.");
    }

    private void startTickTask(){new BukkitRunnable(){public void run(){for(Player p:Bukkit.getOnlinePlayers()){
        UUID ownerId=hookedBy.get(p.getUniqueId());if(ownerId!=null){Player owner=Bukkit.getPlayer(ownerId);if(owner==null||!owner.isOnline()||owner.getWorld()!=p.getWorld()||owner.getLocation().distance(p.getLocation())>=getConfig().getDouble("custom.blokada-elytry-dystans",30)){hookedBy.remove(p.getUniqueId());msg(p,"&aBlokada Elytry zdjęta.");}else if(p.isGliding())p.setGliding(false);}
        String held=id(p.getInventory().getItemInMainHand(),itemKey);if("mini".equals(held)){if(!miniUsers.containsKey(p.getUniqueId())){Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"attribute "+p.getName()+" minecraft:scale base set 0.5");miniUsers.put(p.getUniqueId(),System.currentTimeMillis());}}else if(miniUsers.remove(p.getUniqueId())!=null){Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"attribute "+p.getName()+" minecraft:scale base set 1");}
    }}}.runTaskTimer(this,1L,2L);}

    private int stat(Player p,String key){return data.getInt("stats."+p.getUniqueId()+"."+key,0);}
    private void incStat(Player p,String key,int n){data.set("stats."+p.getUniqueId()+"."+key,stat(p,key)+n);saveData();}
    private void showProfile(CommandSender s,Player p){
        int k=stat(p,"kills"),d=stat(p,"deaths"); double kd=d==0?k:(double)k/d;
        msg(s,"&b⚡ PROFIL STORM: &f"+p.getName());
        msg(s,"&7⚔ Zabójstwa: &f"+k+" &8| &7💀 Śmierci: &f"+d+" &8| &7K/D: &f"+String.format(Locale.US,"%.2f",kd));
        msg(s,"&7📦 Otwarte skrzynie: &f"+stat(p,"crates")+" &8| &7🎰 Jackpoty: &f"+stat(p,"jackpots")+" &8| &7Airdropy: &f"+stat(p,"airdrops"));
        msg(s,"&7🏆 Osiągnięcia: &f"+unlockedCount(p)+"/7");
    }
    private int unlockedCount(Player p){return data.getStringList("achievements."+p.getUniqueId()).size();}
    private void unlock(Player p,String id,String title){List<String> a=new ArrayList<>(data.getStringList("achievements."+p.getUniqueId()));if(a.contains(id))return;a.add(id);data.set("achievements."+p.getUniqueId(),a);saveData();Bukkit.broadcastMessage(c("&6[Storm] &e"+p.getName()+" &fzdobył osiągnięcie &b"+title+"&f!"));p.giveExp(50);}
    private void checkAchievements(Player p){int k=stat(p,"kills"),c=stat(p,"crates");if(k>=1)unlock(p,"first_blood","Pierwsza Krew");if(k>=10)unlock(p,"hunter","Łowca");if(k>=100)unlock(p,"massacre","Masakra");if(c>=1)unlock(p,"first_crate","Pierwsza Skrzynia");if(c>=25)unlock(p,"gambler","Hazardzista");if(stat(p,"jackpots")>=1)unlock(p,"jackpot","JACKPOT!");if(stat(p,"airdrops")>=1)unlock(p,"airdrop_hunter","Airdrop Hunter");}
    private void showAchievements(Player p){Inventory inv=Bukkit.createInventory(null,27,c("&6🏆 Osiągnięcia Storm"));String[][] defs={{"first_blood","Pierwsza Krew","Zabij 1 gracza"},{"hunter","Łowca","Zabij 10 graczy"},{"massacre","Masakra","Zabij 100 graczy"},{"first_crate","Pierwsza Skrzynia","Otwórz 1 skrzynię"},{"gambler","Hazardzista","Otwórz 25 skrzyń"},{"jackpot","JACKPOT!","Traf Jackpot Chaosu"},{"airdrop_hunter","Airdrop Hunter","Przejmij Airdrop"}};Set<String> got=new HashSet<>(data.getStringList("achievements."+p.getUniqueId()));for(int i=0;i<defs.length;i++){ItemStack it=new ItemStack(got.contains(defs[i][0])?Material.LIME_DYE:Material.GRAY_DYE);ItemMeta m=it.getItemMeta();m.setDisplayName(c((got.contains(defs[i][0])?"&a✔ ":"&7🔒 ")+defs[i][1]));m.setLore(List.of(c("&7"+defs[i][2]),c(got.contains(defs[i][0])?"&aZdobyte":"&cZablokowane")));it.setItemMeta(m);inv.setItem(10+i,it);}p.openInventory(inv);}

    private void scheduleNextAirdrop(){long min=Math.max(1,getConfig().getLong("airdrop.min-minuty",45)),max=Math.max(min,getConfig().getLong("airdrop.max-minuty",90));long delay=ThreadLocalRandom.current().nextLong(min,max+1)*60L*20L;new BukkitRunnable(){public void run(){spawnAirdrop();scheduleNextAirdrop();}}.runTaskLater(this,delay);}
    private void spawnAirdrop(){World w=Bukkit.getWorlds().get(0);int radius=Math.max(1,getConfig().getInt("airdrop.promien",1500));int x=ThreadLocalRandom.current().nextInt(-radius,radius+1),z=ThreadLocalRandom.current().nextInt(-radius,radius+1);int y=w.getHighestBlockYAt(x,z)+1;Block b=w.getBlockAt(x,y,z);b.setType(Material.CHEST);if(!(b.getState() instanceof org.bukkit.block.Chest ch))return;ch.getPersistentDataContainer().set(airdropKey,PersistentDataType.INTEGER,1);fillAirdrop(ch.getBlockInventory());ch.update(true);String lk=locKey(b);lockedAirdrops.add(lk);Bukkit.broadcastMessage(c("&b⚡ AIRDROP NADCHODZI! &fX: &e~"+(Math.round(x/100.0)*100)+" &fZ: &e~"+(Math.round(z/100.0)*100)));new BukkitRunnable(){public void run(){lockedAirdrops.remove(lk);Bukkit.broadcastMessage(c("&a🔓 AIRDROP ODBLOKOWANY! &7X: "+x+" Z: "+z));}}.runTaskLater(this,20L*30);}
    private void fillAirdrop(Inventory inv){List<ItemStack> pool=List.of(new ItemStack(Material.DIAMOND,8),new ItemStack(Material.GOLDEN_APPLE,8),new ItemStack(Material.TNT,16),new ItemStack(Material.ENDER_PEARL,8),new ItemStack(Material.EXPERIENCE_BOTTLE,16),new ItemStack(Material.NETHERITE_INGOT,1),key("rzadka"),key("epicka"),key("mityczna"));int n=ThreadLocalRandom.current().nextInt(4,8);for(int i=0;i<n;i++){int slot;do{slot=ThreadLocalRandom.current().nextInt(inv.getSize());}while(inv.getItem(slot)!=null);inv.setItem(slot,pool.get(ThreadLocalRandom.current().nextInt(pool.size())).clone());}}
    @EventHandler public void onAirdropOpen(PlayerInteractEvent e){if(e.getHand()!=EquipmentSlot.HAND)return;if(e.getAction()!=Action.RIGHT_CLICK_BLOCK||e.getClickedBlock()==null)return;Block b=e.getClickedBlock();if(!(b.getState() instanceof org.bukkit.block.Chest ch)||!ch.getPersistentDataContainer().has(airdropKey,PersistentDataType.INTEGER))return;String lk=locKey(b);if(lockedAirdrops.contains(lk)){e.setCancelled(true);msg(e.getPlayer(),"&cAirdrop jest jeszcze zablokowany!");return;}if(claimedAirdrops.add(lk)){incStat(e.getPlayer(),"airdrops",1);checkAchievements(e.getPlayer());Bukkit.broadcastMessage(c("&6[Storm] &e"+e.getPlayer().getName()+" &fjako pierwszy otworzył Airdrop!"));}}

    private double money(UUID u){return data.getDouble("money."+u,0.0);}
    private void addMoney(UUID u,double d){data.set("money."+u,Math.max(0,money(u)+d));saveData();}
    private void sell(Player p,String mode){double sum=0;int count=0;if(mode.equalsIgnoreCase("wszystko")){for(int i=0;i<36;i++){ItemStack it=p.getInventory().getItem(i);if(it==null||it.getType().isAir()||id(it,itemKey)!=null||id(it,keyKey)!=null||id(it,crateKey)!=null)continue;sum+=sellPrice(it);count+=it.getAmount();p.getInventory().setItem(i,null);}}else{ItemStack it=p.getInventory().getItemInMainHand();if(it==null||it.getType().isAir()||id(it,itemKey)!=null||id(it,keyKey)!=null||id(it,crateKey)!=null){msg(p,"&cTego nie możesz sprzedać.");return;}sum=sellPrice(it);count=it.getAmount();p.getInventory().setItemInMainHand(null);}addMoney(p.getUniqueId(),sum);msg(p,"Sprzedano &e"+count+" &fprzedmiotów za &a"+sum+" "+getConfig().getString("waluta-symbol","$"));}
    private double sellPrice(ItemStack it){double each=getConfig().getDouble("sprzedaz.ceny."+it.getType().name(),getConfig().getDouble("sprzedaz.domyslna-cena-za-sztuke",1));return each*it.getAmount();}

    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] x){
        if(x.length==1)return filter(List.of("saldo","sprzedaj","skrzynie","klucze","item","projekt","profil","osiagniecia","airdrop"),x[0]);
        if(x.length==2&&x[0].equalsIgnoreCase("sprzedaj"))return filter(List.of("reka","wszystko"),x[1]);
        if(x.length==2&&(x[0].equalsIgnoreCase("skrzynie")||x[0].equalsIgnoreCase("klucze")||x[0].equalsIgnoreCase("item")))return filter(List.of("give","kup"),x[1]);
        if(x.length==3&&x[0].equalsIgnoreCase("klucze")&&x[1].equalsIgnoreCase("kup"))return filter(List.of("rzadka","epicka","mityczna","custom"),x[2]);
        if(x.length==3&&x[1].equalsIgnoreCase("give")){List<String> l=new ArrayList<>(List.of("@p"));for(Player p:Bukkit.getOnlinePlayers())l.add(p.getName());return filter(l,x[2]);}
        if(x.length==4&&x[1].equalsIgnoreCase("give")){return filter(x[0].equalsIgnoreCase("item")?Arrays.asList(ITEMS):Arrays.asList(CRATES),x[3]);}
        return List.of();
    }
    private List<String> filter(List<String> l,String q){String z=q.toLowerCase(Locale.ROOT);return l.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(z)).toList();}
}
