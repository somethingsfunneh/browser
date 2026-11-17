import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/** Browser shell wired to the custom DOM, CSS, layout and script subset. */
public class Browser extends JFrame {
    private static final String HOME = "https://example.com";
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
    private final JTextField address = new JTextField(HOME);
    private final JLabel status = new JLabel("Ready");
    private final JTabbedPane tabs = new JTabbedPane();

    public Browser() {
        super("Custom Java Browser"); setDefaultCloseOperation(EXIT_ON_CLOSE); setSize(1100,750); setMinimumSize(new Dimension(700,450)); setLocationRelativeTo(null); setLayout(new BorderLayout());
        JPanel bar = new JPanel(new BorderLayout(6,0)); JButton back=new JButton("←"), forward=new JButton("→"), go=new JButton("Go"), fresh=new JButton("+"); JPanel nav=new JPanel(); nav.add(back);nav.add(forward);nav.add(fresh);bar.add(nav,BorderLayout.WEST);bar.add(address,BorderLayout.CENTER);bar.add(go,BorderLayout.EAST);add(bar,BorderLayout.NORTH);add(tabs,BorderLayout.CENTER);status.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));add(status,BorderLayout.SOUTH);
        go.addActionListener(e->navigate(address.getText()));address.addActionListener(e->navigate(address.getText()));fresh.addActionListener(e->newTab(HOME));back.addActionListener(e->history(false));forward.addActionListener(e->history(true));tabs.addChangeListener(e->syncAddress());
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control L"),"focusAddress");getRootPane().getActionMap().put("focusAddress",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){address.requestFocusInWindow();address.selectAll();}});
        newTab(HOME);
    }
    private void newTab(String url){ EnginePage page=new EnginePage(); PageState state=new PageState(page); page.setLinkHandler(target->navigate(target)); JScrollPane scroll=new JScrollPane(page);tabs.addTab("New Tab",scroll);tabs.setSelectedComponent(scroll);state.scroll=scroll;scroll.putClientProperty("state",state);load(state,url,true); }
    private PageState active(){ java.awt.Component c=tabs.getSelectedComponent(); if(c instanceof JScrollPane p && p.getClientProperty("state") instanceof PageState s)return s;return null; }
    private void navigate(String input){PageState s=active();if(s==null){newTab(input);return;}load(s,input,true);}
    private void history(boolean forward){PageState s=active();if(s==null)return;Deque<String> from=forward?s.forward:s.back,to=forward?s.back:s.forward;if(from.isEmpty())return;String target=from.pop();if(!s.url.isBlank())to.push(s.url);load(s,target,false);}
    private void load(PageState state,String input,boolean record){String target=input==null?"":input.trim();if(target.isEmpty())return;if(!target.matches("(?i)^https?://.*")){if(target.matches("(?i)^[a-z][a-z0-9+.-]*:.*")){status.setText("Only HTTP and HTTPS are supported");return;}target="https://"+target;}if(record&&!state.url.isBlank()&&!state.url.equals(target)){state.back.push(state.url);state.forward.clear();}state.url=target;syncAddress();status.setText("Loading "+target);
        final String requestUrl=target; SwingWorker<Payload,Void> worker=new SwingWorker<>(){protected Payload doInBackground()throws Exception{HttpRequest request=HttpRequest.newBuilder(URI.create(requestUrl)).timeout(Duration.ofSeconds(25)).header("User-Agent","CustomJavaBrowser/0.1").GET().build();HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));return new Payload(response.body(),response.uri().toString(),response.statusCode());}protected void done(){try{Payload p=get();if(p.code<200||p.code>=400)throw new IllegalStateException("HTTP "+p.code);state.url=p.url;state.page.setPage(p.html,p.url);String title=state.page.pageTitle();int i=tabs.indexOfComponent(state.scroll);if(i>=0)tabs.setTitleAt(i,title.isBlank()?p.url:title);status.setText("Done");syncAddress();}catch(Exception ex){state.page.setPage("<html><body><h1>Page load failed</h1><p>"+escape(ex.getMessage())+"</p></body></html>",requestUrl);status.setText("Load failed");}}};worker.execute();
    }
    private void syncAddress(){PageState s=active();if(s!=null)address.setText(s.url);}
    private void historyButtonsUnused(){}
    private static String escape(String text){return String.valueOf(text).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private static final class Payload{final String html,url;final int code;Payload(String h,String u,int c){html=h;url=u;code=c;}}
    private static final class PageState{final EnginePage page;final Deque<String> back=new ArrayDeque<>(),forward=new ArrayDeque<>();String url="";JScrollPane scroll;PageState(EnginePage p){page=p;}}
    public static void main(String[] args){SwingUtilities.invokeLater(()->new Browser().setVisible(true));}
}
