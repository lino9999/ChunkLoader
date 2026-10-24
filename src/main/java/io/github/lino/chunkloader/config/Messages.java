package io.github.lino.chunkloader.config;

import java.io.File;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

public final class Messages {
    private final YamlConfiguration configuration;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public Messages(File file) throws Exception {
        configuration = new YamlConfiguration();
        configuration.load(file);
    }

    public Component text(String key, String... placeholders) {
        TagResolver.Builder resolver = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            resolver.resolver(Placeholder.unparsed(placeholders[i], placeholders[i + 1]));
        }
        return miniMessage.deserialize(configuration.getString(key, key), resolver.build());
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(text("prefix").append(text(key, placeholders)));
    }
}
