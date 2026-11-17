package engine;

/** Built-in user-agent stylesheet for common semantic HTML elements. */
final class BrowserStyles {
    private BrowserStyles() {}

    static String css() {
        return """
                html, body { color: #202124; background-color: #fff; font-family: Arial, Helvetica, sans-serif; font-size: 16px; }
                body { display: block; margin: 8px; }
                div, main, article, section, nav, header, footer, aside, address, blockquote, form, fieldset, figure { display: block; }
                span, a, strong, b, em, i, small, label, code, q, abbr, sub, sup { display: inline; }
                h1 { display: block; color: #202124; font-size: 32px; font-weight: bold; margin: 0.67em 0; }
                h2 { display: block; color: #202124; font-size: 24px; font-weight: bold; margin: 0.83em 0; }
                h3 { display: block; font-size: 19px; font-weight: bold; margin: 1em 0; }
                h4 { display: block; font-size: 16px; font-weight: bold; margin: 1.33em 0; }
                h5 { display: block; font-size: 13px; font-weight: bold; margin: 1.67em 0; }
                h6 { display: block; font-size: 11px; font-weight: bold; margin: 2.33em 0; }
                p { display: block; margin: 1em 0; }
                a { color: #1558d6; text-decoration: underline; }
                strong, b { font-weight: bold; }
                em, i, cite, dfn, var { font-style: italic; }
                small { font-size: 80%; }
                mark { color: #202124; background-color: #fff2a8; }
                del, s, strike { text-decoration: line-through; }
                ins, u { text-decoration: underline; }
                sub { vertical-align: sub; font-size: smaller; }
                sup { vertical-align: super; font-size: smaller; }
                ul, ol { display: block; margin: 1em 0; padding-left: 40px; }
                li { display: list-item; margin: 0.25em 0; }
                dl { display: block; margin: 1em 0; }
                dt { display: block; font-weight: bold; }
                dd { display: block; margin-left: 40px; }
                blockquote { margin: 1em 40px; }
                pre { display: block; white-space: pre; font-family: Monospaced; font-size: 13px; margin: 1em 0; }
                code, kbd, samp, tt { font-family: Monospaced; font-size: 13px; }
                hr { display: block; color: #9aa0a6; margin: 0.5em auto; }
                figure { margin: 1em 40px; }
                figcaption { display: block; color: #5f6368; font-size: 14px; }
                fieldset { display: block; border: 2px groove #cbd5e1; padding: 0.35em 0.75em 0.625em; margin: 0 2px; }
                legend { font-weight: bold; }
                table { display: table; border-collapse: separate; border-spacing: 2px; }
                caption { display: table-caption; font-weight: bold; text-align: center; }
                thead { display: table-header-group; }
                tbody { display: table-row-group; }
                tfoot { display: table-footer-group; }
                tr { display: table-row; }
                th { display: table-cell; font-weight: bold; text-align: center; }
                td { display: table-cell; }
                button, input, select, textarea { font: inherit; }
                [hidden] { display: none; }
                """;
    }
}
