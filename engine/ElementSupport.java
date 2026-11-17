package engine;

import java.util.Locale;

/** HTML element classification helpers for common modern documents. */
final class ElementSupport {
    static boolean isVoidElement(String tag) {
        return switch (tag) { case "area","base","br","col","embed","hr","img","input","link","meta","param","source","track","wbr" -> true; default -> false; };
    }
    static boolean isBlockElement(String tag) {
        return switch (tag) {
            case "address","article","aside","blockquote","body","br","canvas","dd","div","dl","dt","fieldset","figcaption","figure","footer","form","h1","h2","h3","h4","h5","h6","header","hr","li","main","menu","nav","noscript","ol","p","pre","section","search","table","tbody","td","tfoot","th","thead","tr","ul","dialog","details","summary" -> true;
            default -> false;
        };
    }
    static boolean isInlineElement(String tag) {
        return switch (tag) {
            case "a","abbr","acronym","b","bdi","bdo","big","button","cite","code","data","del","dfn","em","font","i","ins","kbd","label","mark","output","q","ruby","rp","rt","s","samp","small","span","strike","strong","sub","sup","time","tt","u","var" -> true;
            default -> false;
        };
    }
    static boolean isRawTextElement(String tag) { return tag.equals("script") || tag.equals("style") || tag.equals("textarea") || tag.equals("title") || tag.equals("xmp") || tag.equals("plaintext"); }
    static boolean isObsoleteSupported(String tag) {
        return switch (tag) { case "acronym","applet","basefont","big","blink","center","dir","font","frame","frameset","marquee","nobr","noframes","plaintext","s","strike","tt","xmp" -> true; default -> false; };
    }
}
