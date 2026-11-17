package engine;

/** Built-in user-agent stylesheet for the browser's supported HTML subset. */
final class BrowserStyles {
    private BrowserStyles() {}

    static String css() {
        return """
                /* Document defaults */
                html, body {
                    color: #202124;
                    background-color: #ffffff;
                    font-family: Arial, Helvetica, sans-serif;
                    font-size: 16px;
                }
                body { margin: 12px; }
                main, article, section, nav, header, footer, aside, div { color: inherit; }

                /* Headings */
                h1 { color: #172554; font-size: 32px; font-weight: bold; margin: 18px; }
                h2 { color: #1e3a8a; font-size: 27px; font-weight: bold; margin: 16px; }
                h3 { color: #1f2937; font-size: 23px; font-weight: bold; margin: 14px; }
                h4 { color: #1f2937; font-size: 20px; font-weight: bold; margin: 12px; }
                h5 { color: #374151; font-size: 18px; font-weight: bold; margin: 10px; }
                h6 { color: #4b5563; font-size: 16px; font-weight: bold; margin: 10px; }

                /* Text and semantic emphasis */
                p { color: #202124; font-size: 16px; margin: 12px; }
                small { color: #5f6368; font-size: 13px; }
                strong, b { font-weight: bold; }
                em, i, cite, dfn, var { font-style: italic; }
                mark { color: #202124; background-color: #fff2a8; }
                del, s, strike { color: #6b7280; text-decoration: line-through; }
                ins, u { text-decoration: underline; }
                sub, sup { color: #4b5563; font-size: 12px; }
                abbr, acronym { color: #374151; }
                address { color: #4b5563; font-style: italic; margin: 12px; }

                /* Links */
                a { color: #1558d6; text-decoration: underline; }
                a:visited { color: #6b3fa0; }

                /* Lists and quotations */
                ul, ol { color: #202124; padding: 8px; margin: 8px; }
                li { color: #202124; margin: 4px; }
                dl { margin: 10px; }
                dt { color: #1f2937; font-weight: bold; margin: 6px; }
                dd { color: #4b5563; margin: 8px; }
                blockquote { color: #4b5563; background-color: #f5f7fa; border: 1px solid #d5dbe5; padding: 10px; margin: 12px; }
                q { color: #374151; font-style: italic; }

                /* Code and preformatted content */
                pre { color: #e5e7eb; background-color: #1f2937; font-family: Monospaced; font-size: 14px; padding: 10px; margin: 12px; border: 1px solid #374151; }
                code, kbd, samp, tt { color: #9f1239; background-color: #f3f4f6; font-family: Monospaced; font-size: 14px; }
                pre code { color: #e5e7eb; background-color: #1f2937; }

                /* Separators and common containers */
                hr { color: #cbd5e1; margin: 12px; }
                figure { margin: 12px; }
                figcaption { color: #64748b; font-size: 14px; margin: 6px; }
                fieldset { border: 1px solid #cbd5e1; padding: 8px; margin: 10px; }
                legend { color: #334155; font-weight: bold; }
                details, summary { color: #334155; margin: 8px; }
                
                /* Table content is rendered in document flow; these rules provide readable defaults. */
                table { color: #202124; font-size: 15px; border: 1px solid #cbd5e1; margin: 10px; }
                caption { color: #475569; font-weight: bold; margin: 6px; }
                thead, th { color: #1e293b; background-color: #eaf0f8; font-weight: bold; }
                tbody, td { color: #202124; }
                tr { margin: 2px; }
                """;
    }
}
