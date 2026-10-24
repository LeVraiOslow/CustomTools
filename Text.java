package fr.customtools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern XHEX = Pattern.compile("[&\u00a7][xX](?:[&\u00a7][0-9a-fA-F]){6}");
    private static final Pattern HEX = Pattern.compile("[&\u00a7]#([0-9a-fA-F]{6})");
    private static final Pattern CODE = Pattern.compile("[&\u00a7]([0-9a-fk-orA-FK-OR])");

    private Text() {
    }

    public static Component parse(String input, TagResolver ... resolvers) {
        return MM.deserialize(Text.toMini(input), resolvers);
    }

    public static String toMini(String input) {
        if (input == null) {
            return "";
        }
        String s = XHEX.matcher(input).replaceAll(m -> {
            String hex = m.group().replaceAll("[&\u00a7xX]", "");
            return "<reset><#" + hex + ">";
        });
        s = HEX.matcher(s).replaceAll(m -> "<reset><#" + m.group(1) + ">");
        s = CODE.matcher(s).replaceAll(m -> Matcher.quoteReplacement(Text.tag(Character.toLowerCase(m.group(1).charAt(0)))));
        return s;
    }

    private static String tag(char c) {
        return switch (c) {
            case '0' -> "<reset><black>";
            case '1' -> "<reset><dark_blue>";
            case '2' -> "<reset><dark_green>";
            case '3' -> "<reset><dark_aqua>";
            case '4' -> "<reset><dark_red>";
            case '5' -> "<reset><dark_purple>";
            case '6' -> "<reset><gold>";
            case '7' -> "<reset><gray>";
            case '8' -> "<reset><dark_gray>";
            case '9' -> "<reset><blue>";
            case 'a' -> "<reset><green>";
            case 'b' -> "<reset><aqua>";
            case 'c' -> "<reset><red>";
            case 'd' -> "<reset><light_purple>";
            case 'e' -> "<reset><yellow>";
            case 'f' -> "<reset><white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            default -> "<reset>";
        };
    }
}

