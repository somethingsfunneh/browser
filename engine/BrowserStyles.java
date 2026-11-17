package engine;

/** Expanded built-in HTML user-agent stylesheet. */
final class BrowserStyles {
    private BrowserStyles() {}
    static String css() {
        return """
                html, body { color:#202124; background-color:#fff; font-family:Arial,Helvetica,sans-serif; font-size:16px; }
                body { display:block; margin:8px; }
                div,main,article,section,nav,header,footer,aside,address,blockquote,form,fieldset,figure,details,dialog,search,menu { display:block; }
                span,a,strong,b,em,i,small,label,code,q,abbr,sub,sup,cite,dfn,kbd,samp,var,time,mark { display:inline; }
                h1 { display:block; font-size:32px; font-weight:bold; margin:0.67em 0; }
                h2 { display:block; font-size:24px; font-weight:bold; margin:0.83em 0; }
                h3 { display:block; font-size:19px; font-weight:bold; margin:1em 0; }
                h4 { display:block; font-size:16px; font-weight:bold; margin:1.33em 0; }
                h5 { display:block; font-size:13px; font-weight:bold; margin:1.67em 0; }
                h6 { display:block; font-size:11px; font-weight:bold; margin:2.33em 0; }
                p { display:block; margin:1em 0; }
                a { color:#1558d6; text-decoration:underline; }
                strong,b { font-weight:bold; } em,i,cite,dfn,var { font-style:italic; }
                small { font-size:80%; } big { font-size:120%; }
                mark { color:#202124; background-color:#fff2a8; }
                del,s,strike { text-decoration:line-through; } ins,u { text-decoration:underline; }
                sub { vertical-align:sub; font-size:smaller; } sup { vertical-align:super; font-size:smaller; }
                ul,menu { display:block; list-style-type:disc; margin:1em 0; padding-left:40px; }
                ol { display:block; list-style-type:decimal; margin:1em 0; padding-left:40px; }
                li { display:list-item; margin:0.25em 0; }
                dl { display:block; margin:1em 0; } dt { display:block; font-weight:bold; } dd { display:block; margin-left:40px; }
                blockquote { margin:1em 40px; } q { quotes:'“' '”' '‘' '’'; }
                pre { display:block; white-space:pre; font-family:Monospaced; font-size:13px; margin:1em 0; }
                code,kbd,samp,tt,pre { font-family:Monospaced; } code,kbd,samp,tt { font-size:13px; }
                kbd { border:1px solid #bbb; border-radius:3px; padding:1px 3px; background:#f7f7f7; }
                hr { display:block; color:#9aa0a6; margin:0.5em auto; border:1px inset #ddd; }
                figure { margin:1em 40px; } figcaption { display:block; color:#5f6368; font-size:14px; text-align:center; }
                fieldset { display:block; border:2px groove #cbd5e1; padding:0.35em 0.75em 0.625em; margin:0 2px; }
                legend { display:block; padding:0 2px; font-weight:bold; }
                details { display:block; } summary { display:block; font-weight:bold; cursor:pointer; }
                dialog { border:1px solid #aaa; padding:1em; background:#fff; }
                table { display:table; border-collapse:separate; border-spacing:2px; } caption { display:table-caption; font-weight:bold; text-align:center; }
                thead { display:table-header-group; } tbody { display:table-row-group; } tfoot { display:table-footer-group; }
                tr { display:table-row; } th { display:table-cell; font-weight:bold; text-align:center; padding:1px; }
                td { display:table-cell; padding:1px; }
                colgroup,col { display:table-column; } thead th,tbody th,tfoot th { background-color:#f1f3f4; }
                img,picture,svg,canvas,video,audio { display:inline-block; max-width:100%; }
                picture { display:inline-block; } svg { overflow:hidden; }
                canvas { background:#fff; }
                audio,video { background:#000; }
                label { cursor:pointer; } output { display:inline; }
                meter,progress { display:inline-block; height:1em; width:10em; }
                button,input,select,textarea { font:inherit; }
                button { display:inline-block; border:1px solid #aaa; border-radius:4px; padding:2px 6px; background:#f3f3f3; cursor:pointer; }
                input { display:inline-block; border:1px solid #aaa; padding:2px; background:#fff; }
                textarea { display:inline-block; border:1px solid #aaa; padding:2px; background:#fff; resize:both; }
                select { display:inline-block; border:1px solid #aaa; background:#fff; }
                option,optgroup { display:block; }
                [hidden],template,noscript { display:none; }
                """;
    }
}
