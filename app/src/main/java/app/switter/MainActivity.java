package app.switter;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.method.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private Vault vault;
    private final Network network=new Network();
    private final ExecutorService workers=Executors.newFixedThreadPool(6);
    private final Map<String,java.util.List<Post>> cache=new HashMap<>();
    private final Map<String,String> errors=new HashMap<>();
    private FrameLayout root;
    private LinearLayout shell,body,top;
    private TextView subtitle;
    private FrameLayout gate;
    private AlertDialog dialog;
    private WebView web;
    private SwipeRefreshLayout swipe;
    private boolean dark,locked,busy,refreshing;
    private int bg,fg,muted,surface,accent,line,screenVersion;
    private String screen="feed",blogId="";
    private EditText draftTitle,draftText;
    private TextView photoLabel;
    private ImageView photoPreview;
    private byte[] photo;
    private String photoMime="image/jpeg";
    private boolean photoLoading;
    private LinearLayout editor;
    private static final int PICK_IMAGE=10;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        try{vault=new Vault(this);}catch(Exception e){
            new AlertDialog.Builder(this).setTitle("Не удалось открыть хранилище").setMessage("Зашифрованные данные недоступны. Повторно откройте приложение. "+e.getMessage()).setPositiveButton("Закрыть",(d,w)->finish()).setCancelable(false).show();return;
        }
        dark=vault.theme.equals("dark")||(vault.theme.equals("system")&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark?R.style.AppThemeDark:R.style.AppTheme);
        bg=Color.parseColor(dark?"#101817":"#F4F6F4");surface=Color.parseColor(dark?"#1A2624":"#FFFFFF");
        fg=Color.parseColor(dark?"#E8F1EC":"#172F2B");muted=Color.parseColor(dark?"#A4BAB1":"#5C736A");
        accent=Color.parseColor(dark?"#71D7C7":"#117C72");line=Color.parseColor(dark?"#30433D":"#DFE8E2");
        root=new FrameLayout(this);root.setBackgroundColor(bg);root.setFitsSystemWindows(true);setContentView(root);
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        if(Build.VERSION.SDK_INT>=27&&!dark)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        shell=column();root.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        locked=vault.protectedByPin();showFeed("feed","");if(locked)lock();else refresh();
    }
    @Override protected void onResume(){super.onResume();if(vault!=null&&vault.protectedByPin()&&locked)lock();}
    @Override protected void onPause(){
        if(vault!=null&&vault.protectedByPin()){
            locked=true;if(dialog!=null)dialog.dismiss();lock();
            if(web!=null)web.onPause();
        }
        super.onPause();
    }
    @Override protected void onDestroy(){network.clear();workers.shutdownNow();if(web!=null)web.destroy();super.onDestroy();}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(3),1);return v;}
    private TextView label(LinearLayout parent,String s,int size,int color){TextView t=text(s,size,color);parent.addView(t);return t;}
    private Button button(String title,Runnable fn){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(accent);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(14),dp(6),dp(14),dp(6));
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((accent&0x00ffffff)|0x30000000),rounded((accent&0x00ffffff)|0x12000000,14),null));
        b.setOnClickListener(v->{if(!locked)fn.run();});return b;}
    private void strong(TextView t){t.setTypeface(null,Typeface.BOLD);}
    private void gap(LinearLayout p,int h){View v=new View(this);p.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private LinearLayout card(LinearLayout p){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(14));c.setBackground(rounded(surface,20));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(16),dp(7),dp(16),dp(7));p.addView(c,lp);return c;}
    private ScrollView scroller(LinearLayout parent){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.setClipToPadding(false);parent.addView(s,new LinearLayout.LayoutParams(-1,0,1));return s;}
    private void base(String title,String detail){
        screenVersion++;
        if(web!=null){web.stopLoading();web.destroy();web=null;}
        shell.removeAllViews();top=column();top.setPadding(dp(22),dp(18),dp(16),dp(10));shell.addView(top);
        LinearLayout heading=row();TextView name=text(title,30,fg);strong(name);heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(button("＋",()->navigate(()->compose(null))),new LinearLayout.LayoutParams(dp(48),dp(48)));top.addView(heading);
        subtitle=label(top,detail,13,muted);gap(top,6);
        body=column();shell.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=row();nav.setPadding(dp(5),dp(4),dp(5),dp(6));nav.setBackgroundColor(surface);
        String[] names={"Лента","Мой блог","Подписки","Настройки"};
        for(int i=0;i<names.length;i++){final int tab=i;Button b=button(names[i],()->navigate(()->{if(tab==0)showFeed("feed","");if(tab==1)showFeed("own",vault.ownId);if(tab==2)subscriptions();if(tab==3)settings();}));
            boolean selected=(tab==0&&screen.equals("feed"))||(tab==1&&screen.equals("own"))||(tab==2&&(screen.equals("subscriptions")||screen.equals("blog")))||(tab==3&&screen.equals("settings"));
            b.setBackground(rounded(selected?((accent&0x00ffffff)|0x18000000):Color.TRANSPARENT,16));
            if(selected)strong(b);b.setTextSize(11);b.setPadding(0,0,0,0);nav.addView(b,new LinearLayout.LayoutParams(0,dp(56),1));}
        shell.addView(nav);
    }
    private Blog blog(String id){for(Blog b:vault.blogs)if(b.id.equals(id))return b;return null;}
    private void showFeed(String type,String id){
        screen=type;blogId=id;
        Blog selected=blog(id);
        base(type.equals("feed")?"switter":selected==null?"Мой блог":selected.name,type.equals("feed")?"Все ваши блоги · сначала новое":"Потяните вниз, чтобы обновить");
        if(type.equals("own")&&vault.own()==null){welcome();return;}
        if(type.equals("feed")&&vault.blogs.isEmpty()){welcome();return;}
        if(type.equals("own")&&selected!=null){LinearLayout actions=row();actions.setPadding(dp(16),0,dp(16),0);
            actions.addView(button("Написать",()->compose(null)));
            actions.addView(button("Архив",()->browser(selected,selected.base().resolve("archive/").toString())));
            actions.addView(button("Ещё",()->ownMenu(selected)));top.addView(actions);
        }
        swipe=new SwipeRefreshLayout(this);swipe.setColorSchemeColors(accent);swipe.setProgressBackgroundColorSchemeColor(surface);
        body.addView(swipe,new LinearLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this);LinearLayout list=column();list.setPadding(0,dp(4),0,dp(20));scroll.addView(list);swipe.addView(scroll);
        swipe.setOnRefreshListener(this::refresh);swipe.setRefreshing(refreshing);
        Collection<java.util.List<Post>> groups=new ArrayList<>();
        for(Blog b:vault.blogs)if(type.equals("feed")||b.id.equals(id)){
            if(cache.containsKey(b.id))groups.add(cache.get(b.id));
            if(errors.containsKey(b.id)){
                LinearLayout c=card(list);strong(label(c,b.name,16,fg));label(c,errors.get(b.id),13,muted);
                c.addView(button("Открыть веб-страницу",()->browser(b,b.url)));
            }
        }
        java.util.List<Post> posts=Content.timeline(groups);
        if(posts.isEmpty()){
            LinearLayout empty=card(list);strong(label(empty,refreshing?"Обновляем ленту…":"Здесь пока тихо",24,fg));gap(empty,8);
            label(empty,"Публикуйте мысли, читайте подписки. Новые записи появятся здесь после обновления.",15,muted);
            empty.addView(button("Обновить",this::refresh));
        }
        for(Post p:posts)postCard(list,p);
    }
    private void welcome(){
        ScrollView scroll=scroller(body);LinearLayout list=column();scroll.addView(list);LinearLayout c=card(list);
        gap(c,18);strong(label(c,"Ваш уголок\nнезависимой сети",28,fg));gap(c,14);
        label(c,"Подключите свой Switter или подпишитесь на другие блоги. Все записи будут в одной ленте.",16,muted);gap(c,24);
        c.addView(button("Подключить мой блог",()->editBlog(null,true)));
        c.addView(button("Добавить подписку",()->editBlog(null,false)));gap(c,18);
    }
    private void postCard(LinearLayout list,Post p){
        LinearLayout c=card(list);TextView source=label(c,p.blog.name+"  ·  "+(p.timestamp==0?"дата не указана":DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm",new Locale("ru")).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(p.timestamp))),12,muted);
        source.setOnClickListener(v->{if(!locked)showFeed("blog",p.blog.id);});gap(c,10);strong(label(c,p.title,22,fg));gap(c,10);
        if(!p.image.isEmpty()){
            ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription(p.title);
            c.addView(image,new LinearLayout.LayoutParams(-1,-2));loadImage(p.blog,p.image,image,screenVersion);gap(c,12);
        }
        TextView content=label(c,"",16,fg);content.setText(Html.fromHtml(p.html?Content.safe(p.content):Content.render(p.content),Html.FROM_HTML_MODE_COMPACT));content.setTextIsSelectable(true);
        content.setMovementMethod(new RoutedLinks(p.blog));gap(c,8);
        LinearLayout actions=row();
        if(!p.blog.id.equals(vault.ownId))actions.addView(button("↗ Репост",()->compose(p)));
        else if(!p.id.isEmpty())actions.addView(button("Удалить",()->confirm("Удалить запись?",p.title,()->mutate(()->network.action(p.blog,"delete",p.id),()->{cache.remove(p.blog.id);refresh();}))));
        c.addView(actions);
    }
    private void refresh(){
        if(refreshing)return;
        if(vault.blogs.isEmpty())return;
        refreshing=true;if(swipe!=null)swipe.setRefreshing(true);
        final java.util.List<Blog> blogs=new ArrayList<>(vault.blogs);final int[] remaining={blogs.size()};
        for(Blog b:blogs)workers.execute(()->{
            java.util.List<Post> posts=null;String error=null;
            try{posts=network.posts(b);}catch(Exception e){error=message(e);}
            final java.util.List<Post> result=posts;final String problem=error;
            runOnUiThread(()->{
                Blog current=blog(b.id);
                if(current!=null&&current.json().toString().equals(b.json().toString())){
                    if(result!=null){cache.put(b.id,result);errors.remove(b.id);}else errors.put(b.id,problem);
                }
                if(--remaining[0]==0){refreshing=false;if(swipe!=null)swipe.setRefreshing(false);
                    if(screen.equals("feed")||screen.equals("own")||screen.equals("blog")){
                        Blog sel=blog(blogId);
                        if(!screen.equals("feed")&&sel!=null&&!cache.containsKey(sel.id)&&errors.containsKey(sel.id)) browser(sel,sel.url);
                        else showFeed(screen,blogId);
                    }
                }
            });
        });
    }
    private void loadImage(Blog b,String url,ImageView view,int version){workers.execute(()->{
        try{byte[] data=network.get(b,url,Network.MAX_IMAGE).bytes;Bitmap bitmap=decode(data);runOnUiThread(()->{if(screenVersion==version)view.setImageBitmap(bitmap);});}
        catch(Exception e){runOnUiThread(()->{if(screenVersion==version){view.setMinimumHeight(dp(60));view.setContentDescription("Изображение не загружено: "+message(e));}});}
    });}
    private Bitmap decode(byte[] data)throws IOException{
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,o);
        if(o.outWidth<=0||o.outHeight<=0)throw new IOException("Неподдерживаемое изображение");
        o.inSampleSize=1;while(o.outWidth/o.inSampleSize>1600||o.outHeight/o.inSampleSize>1600)o.inSampleSize*=2;
        o.inJustDecodeBounds=false;Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length,o);if(b==null)throw new IOException("Не удалось открыть изображение");return b;
    }
    private void ownMenu(Blog b){dialog=new AlertDialog.Builder(this).setItems(new String[]{"Настроить блог","Архивировать все записи"},(d,w)->{
        if(w==0)editBlog(b,true);else confirm("Архивировать все записи?","Switter создаст HTML-архив и очистит текущую ленту блога и uploads. Вернуть записи через клиент нельзя.",()->mutate(()->network.action(b,"archive",""),()->{cache.remove(b.id);refresh();}));
    }).show();}
    private void subscriptions(){
        screen="subscriptions";base("Подписки","Независимые блоги, которые вы читаете");
        ScrollView scroll=scroller(body);LinearLayout list=column();scroll.addView(list);
        LinearLayout add=card(list);add.addView(button("＋ Добавить блог",()->editBlog(null,false)));
        int n=0;for(Blog b:vault.blogs)if(!b.id.equals(vault.ownId)){n++;LinearLayout c=card(list);strong(label(c,b.name,21,fg));label(c,b.url,13,muted);
            label(c,b.proxy?"SOCKS5 · "+b.proxyHost+":"+b.proxyPort:"Прямое подключение",12,muted);
            LinearLayout buttons=row();buttons.addView(button("Читать",()->{showFeed("blog",b.id);if(!cache.containsKey(b.id))refresh();}));
            buttons.addView(button("Настроить",()->editBlog(b,false)));buttons.addView(button("Удалить",()->confirm("Удалить подписку?",b.name,()->{
                vault.blogs.remove(b);cache.remove(b.id);errors.remove(b.id);save();network.clear();subscriptions();})));c.addView(buttons);
        }
        if(n==0){LinearLayout c=card(list);label(c,"Добавьте адрес блога на платформе Switter. При несовместимой разметке откроется веб-страница.",16,muted);}
    }
    private EditText field(LinearLayout parent,String title,String value,boolean secret){
        label(parent,title,13,muted);EditText f=new EditText(this);f.setTextColor(fg);f.setTextSize(16);f.setSingleLine(true);
        f.setInputType(secret?android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_CLASS_TEXT);
        f.setText(value);f.setPadding(dp(8),dp(10),dp(8),dp(10));f.setSaveEnabled(false);parent.addView(f,new LinearLayout.LayoutParams(-1,-2));gap(parent,8);return f;
    }
    private void editBlog(Blog original,boolean owner){
        Blog b=original==null?new Blog():new Blog(original.json());
        LinearLayout form=column();form.setPadding(dp(22),dp(12),dp(22),dp(16));ScrollView scroll=new ScrollView(this);scroll.addView(form);
        EditText name=field(form,"Название",b.name,false),url=field(form,"URL блога (https://example.org/blog/)",b.url,false);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        EditText admin=null,login=null,password=null;
        if(owner){
            admin=field(form,"Путь или полный URL административного скрипта",b.admin,false);
            admin.setHint("secret-panel.php или https://example.org/panel.php");
            login=field(form,"Логин администратора",b.login,false);password=field(form,"Пароль администратора",b.password,true);
        }
        Switch proxy=new Switch(this);proxy.setText("Подключаться через SOCKS5");proxy.setTextColor(fg);proxy.setChecked(b.proxy);form.addView(proxy);gap(form,8);
        EditText host=field(form,"Хост прокси",b.proxyHost,false),port=field(form,"Порт",String.valueOf(b.proxyPort),false);
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        label(form,"SOCKS5 без аутентификации, например Tor: 127.0.0.1:9050. Все запросы и изображения этого блога идут выбранным маршрутом.",12,muted);gap(form,12);
        EditText zone=field(form,"Часовой пояс сервера",b.zone,false);
        label(form,"Например Europe/Moscow или UTC. Switter хранит дату без часового пояса; настройка нужна для правильного порядка ленты.",12,muted);
        final EditText a=admin,l=login,p=password;
        dialog=new AlertDialog.Builder(this).setTitle(owner?"Мой блог":"Подписка").setView(scroll).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();
        AlertDialog current=dialog;current.setOnShowListener(d->current.getButton(-1).setOnClickListener(v->{
            try{
                b.name=name.getText().toString().trim();b.url=url.getText().toString().trim();
                b.proxy=proxy.isChecked();b.proxyHost=host.getText().toString().trim();b.proxyPort=Integer.parseInt(port.getText().toString());b.zone=zone.getText().toString().trim();
                if(owner){b.admin=a.getText().toString().trim();b.login=l.getText().toString();b.password=p.getText().toString();}
                b.validate(owner);
                for(Blog other:vault.blogs)if(!other.id.equals(b.id)&&Blog.requireUrl(other.url).equals(Blog.requireUrl(b.url)))throw new IllegalArgumentException("Этот блог уже добавлен");
                Blog previousOwner=vault.own();if(owner&&previousOwner!=null&&!previousOwner.id.equals(b.id))vault.blogs.remove(previousOwner);
                if(original!=null)vault.blogs.remove(original);vault.blogs.add(b);if(owner)vault.ownId=b.id;
                vault.save();network.clear();cache.remove(b.id);errors.remove(b.id);current.dismiss();showFeed(owner?"own":"blog",b.id);refresh();
            }catch(Exception e){toast(message(e));}
        }));current.show();
    }
    private void settings(){
        screen="settings";base("Настройки","Оформление, доступ и подключение");ScrollView scroll=scroller(body);LinearLayout list=column();scroll.addView(list);
        LinearLayout account=card(list);strong(label(account,"Мой блог",21,fg));label(account,vault.own()==null?"Ещё не подключён":vault.own().url,14,muted);
        account.addView(button("Настроить подключение",()->editBlog(vault.own(),true)));
        LinearLayout themes=card(list);strong(label(themes,"Цветовая тема",21,fg));
        RadioGroup group=new RadioGroup(this);String[] titles={"Как в системе","Светлая","Тёмная"},values={"system","light","dark"};
        for(int i=0;i<titles.length;i++){RadioButton r=new RadioButton(this);r.setId(View.generateViewId());r.setText(titles[i]);r.setTextColor(fg);group.addView(r);if(vault.theme.equals(values[i]))group.check(r.getId());final String value=values[i];r.setOnClickListener(v->{vault.theme=value;save();recreate();});}themes.addView(group);
        LinearLayout security=card(list);strong(label(security,"Защита PIN-кодом",21,fg));
        label(security,vault.protectedByPin()?"Включена · блокировка при уходе из приложения":"Отключена",14,muted);
        security.addView(button(vault.protectedByPin()?"Изменить PIN":"Установить PIN",()->pinSettings(false)));
        if(vault.protectedByPin())security.addView(button("Отключить PIN",()->pinSettings(true)));
        LinearLayout about=card(list);strong(label(about,"switter 1.0",21,fg));
        label(about,"Клиент для one-loner/Switter\nAndroid 8.0 и новее\nПароли и настройки зашифрованы Android Keystore. Резервное копирование отключено.",14,muted);
    }
    private void pinSettings(boolean remove){
        LinearLayout form=column();form.setPadding(dp(22),dp(12),dp(22),dp(16));
        EditText old=vault.protectedByPin()?field(form,"Текущий PIN","",true):null;
        EditText pin=remove?null:field(form,"Новый PIN: 4–12 цифр","",true),repeat=remove?null:field(form,"Повторите новый PIN","",true);
        for(EditText f:new EditText[]{old,pin,repeat})if(f!=null)f.setInputType(18);
        dialog=new AlertDialog.Builder(this).setTitle(remove?"Отключить PIN":"Защита доступа").setView(form).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();
        AlertDialog current=dialog;current.setOnShowListener(d->current.getButton(-1).setOnClickListener(v->{
            String a=old==null?"":old.getText().toString(),b=pin==null?"":pin.getText().toString(),c=repeat==null?"":repeat.getText().toString();
            if(!remove&&(!b.matches("[0-9]{4,12}")||!b.equals(c))){toast("PIN: 4–12 цифр; оба значения должны совпадать");return;}
            current.getButton(-1).setEnabled(false);workers.execute(()->{
                try{
                    if(old!=null&&!vault.unlock(a))throw new IOException("Неверный PIN. Повтор через "+vault.remainingSeconds()+" с");
                    if(remove)vault.removePin();else vault.setPin(b);
                    runOnUiThread(()->{current.dismiss();settings();});
                }catch(Exception e){runOnUiThread(()->{current.getButton(-1).setEnabled(true);toast(message(e));});}
            });
        }));current.show();
    }
    private void lock(){
        if(root==null)return;
        shell.setVisibility(View.INVISIBLE);
        if(gate!=null)return;
        gate=new FrameLayout(this);gate.setBackgroundColor(bg);gate.setClickable(true);gate.setFocusable(true);
        LinearLayout form=column();form.setGravity(Gravity.CENTER_HORIZONTAL);form.setPadding(dp(34),dp(36),dp(34),dp(36));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);gate.addView(form,lp);
        strong(label(form,"switter",36,fg));gap(form,12);label(form,"Введите PIN-код",18,muted);gap(form,18);
        EditText pin=field(form,"PIN","",true);pin.setInputType(18);pin.setGravity(Gravity.CENTER);pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        Button unlock=new Button(this);unlock.setText("Разблокировать");form.addView(unlock);
        unlock.setOnClickListener(v->{
            if(vault.remainingSeconds()>0){toast("Повторите через "+vault.remainingSeconds()+" с");return;}
            unlock.setEnabled(false);String value=pin.getText().toString();
            workers.execute(()->{try{
                boolean ok=vault.unlock(value);runOnUiThread(()->{
                    if(ok){locked=false;root.removeView(gate);gate=null;shell.setVisibility(View.VISIBLE);((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(pin.getWindowToken(),0);if(web!=null)web.onResume();if(cache.isEmpty())refresh();}
                    else{pin.setText("");unlock.setEnabled(true);toast("Неверный PIN. "+(vault.remainingSeconds()>0?"Повтор через "+vault.remainingSeconds()+" с":"Попробуйте ещё раз"));}
                });
            }catch(Exception e){runOnUiThread(()->{unlock.setEnabled(true);toast(message(e));});}});
        });root.addView(gate,new FrameLayout.LayoutParams(-1,-1));
    }
    private void compose(Post repost){
        if(vault.own()==null){toast("Сначала подключите свой блог");editBlog(null,true);return;}
        screen="editor";base(repost==null?"Новая запись":"Репост","Публикация в "+vault.own().name);
        photo=null;photoLoading=false;photoMime="image/jpeg";
        ScrollView scroll=scroller(body);editor=column();editor.setPadding(dp(20),dp(8),dp(20),dp(24));scroll.addView(editor);
        draftTitle=field(editor,"Название записи",repost==null?"":repost.title,false);
        LinearLayout tools=row();HorizontalScrollView toolScroll=new HorizontalScrollView(this);toolScroll.setHorizontalScrollBarEnabled(false);toolScroll.addView(tools);editor.addView(toolScroll);
        String[] titles={"Ж","К","Ч","З","Ссылка"},markers={"**","*","__","~~","link"};
        for(int i=0;i<titles.length;i++){final String marker=markers[i];Button b=button(titles[i],()->format(marker));b.setMinWidth(dp(48));b.setMinimumWidth(dp(48));tools.addView(b);}
        draftText=new EditText(this);draftText.setTextColor(fg);draftText.setTextSize(17);draftText.setGravity(Gravity.TOP);draftText.setMinLines(9);draftText.setHint("О чём вы думаете?");draftText.setHintTextColor(muted);
        draftText.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);draftText.setText(repost==null?"":repost.repostText());draftText.setSaveEnabled(false);editor.addView(draftText,new LinearLayout.LayoutParams(-1,-2));
        gap(editor,12);photoLabel=label(editor,"Без изображения · до 5 МБ",13,muted);
        photoPreview=new ImageView(this);photoPreview.setAdjustViewBounds(true);photoPreview.setMaxHeight(dp(260));photoPreview.setContentDescription("Изображение записи");editor.addView(photoPreview,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout photoActions=row();photoActions.addView(button("Прикрепить",()->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.setType("image/*");pick.addCategory(Intent.CATEGORY_OPENABLE);pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png","image/gif","image/webp"});startActivityForResult(pick,PICK_IMAGE);
        }));photoActions.addView(button("Убрать",()->{photo=null;photoLoading=false;photoPreview.setImageDrawable(null);photoLabel.setText("Без изображения");}));editor.addView(photoActions);
        gap(editor,12);editor.addView(button("Предпросмотр",this::preview));
        Button publish=button("Опубликовать",()->{
            if(photoLoading){toast("Дождитесь загрузки изображения или уберите его");return;}
            Blog owner=vault.own();String title=draftTitle.getText().toString(),content=draftText.getText().toString();byte[] data=photo;String mime=photoMime;
            mutate(()->network.publish(owner,title,content,data,mime),()->{photo=null;draftTitle=null;draftText=null;showFeed("own",vault.ownId);refresh();});
        });publish.setBackground(rounded(accent,16));publish.setTextColor(dark?bg:Color.WHITE);editor.addView(publish,new LinearLayout.LayoutParams(-1,dp(54)));
        if(repost!=null&&!repost.image.isEmpty()){
            photoLoading=true;photoLabel.setText("Загружаем изображение из источника…");int version=screenVersion;
            workers.execute(()->{try{
                Network.Page image=network.get(repost.blog,repost.image,Network.MAX_IMAGE);Network.extension(image.mime);Bitmap bitmap=decode(image.bytes);
                runOnUiThread(()->{if(version==screenVersion&&photoLoading){photo=image.bytes;photoMime=image.mime;photoLoading=false;photoLabel.setText("Изображение из первоисточника");photoPreview.setImageBitmap(bitmap);}});
            }catch(Exception e){runOnUiThread(()->{if(version==screenVersion){photoLabel.setText("Не удалось загрузить изображение. Прикрепите файл или нажмите «Убрать». "+message(e));}});}});
        }
    }
    private void format(String marker){
        if(draftText==null)return;
        int start=Math.max(0,draftText.getSelectionStart()),end=Math.max(start,draftText.getSelectionEnd());
        String selected=draftText.getText().subSequence(start,end).toString();if(selected.isEmpty())selected="текст";
        if(marker.equals("link")){
            EditText url=new EditText(this);url.setHint("https://");url.setSingleLine();final String value=selected;
            dialog=new AlertDialog.Builder(this).setTitle("Ссылка").setView(url).setNegativeButton("Отмена",null).setPositiveButton("Вставить",null).create();
            AlertDialog current=dialog;current.setOnShowListener(d->current.getButton(-1).setOnClickListener(v->{try{
                String u=Blog.requireUrl(url.getText().toString().trim()).toString();draftText.getText().replace(start,end,"["+value+"]("+u+")");current.dismiss();
            }catch(Exception e){toast(message(e));}}));current.show();
        }else{draftText.getText().replace(start,end,marker+selected+marker);draftText.requestFocus();draftText.setSelection(start+marker.length(),start+marker.length()+selected.length());}
    }
    private void preview(){
        LinearLayout page=column();page.setPadding(dp(20),dp(16),dp(20),dp(20));ScrollView scroll=new ScrollView(this);scroll.addView(page);
        strong(label(page,draftTitle.getText().toString(),24,fg));gap(page,12);
        if(photo!=null){try{ImageView image=new ImageView(this);image.setImageBitmap(decode(photo));image.setAdjustViewBounds(true);page.addView(image,new LinearLayout.LayoutParams(-1,-2));}catch(Exception e){toast(message(e));}}
        TextView content=label(page,"",17,fg);content.setText(Html.fromHtml(Content.render(draftText.getText().toString()),Html.FROM_HTML_MODE_COMPACT));
        dialog=new AlertDialog.Builder(this).setTitle("Предпросмотр").setView(scroll).setPositiveButton("Продолжить",null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_IMAGE&&result==RESULT_OK&&data!=null&&data.getData()!=null&&screen.equals("editor")){
            Uri uri=data.getData();photoLoading=true;photoLabel.setText("Читаем изображение…");final int version=screenVersion;
            workers.execute(()->{try{
                String mime=getContentResolver().getType(uri);if(mime==null)throw new IOException("Неизвестный тип файла");Network.extension(mime);
                byte[] bytes=Network.read(getContentResolver().openInputStream(uri),Network.MAX_IMAGE);Bitmap bitmap=decode(bytes);
                runOnUiThread(()->{if(version==screenVersion){photo=bytes;photoMime=mime;photoLoading=false;photoPreview.setImageBitmap(bitmap);photoLabel.setText("Изображение · "+bytes.length/1024+" КБ");}});
            }catch(Exception e){runOnUiThread(()->{if(version==screenVersion){photoLoading=false;photoLabel.setText(photo==null?"Без изображения":"Предыдущее изображение сохранено");toast(message(e));}});}});
        }
    }
    /** WebView is a renderer only: every remote resource goes through this blog's client. */
    private void browser(Blog b,String url){
        screen="web";blogId=b.id;base(b.name,"Веб-страница · "+(b.proxy?"SOCKS5":"прямое подключение"));
        Button back=button("← К ленте",()->showFeed("blog",b.id));top.addView(back);
        swipe=new SwipeRefreshLayout(this);swipe.setColorSchemeColors(accent);body.addView(swipe,new LinearLayout.LayoutParams(-1,-1));
        web=new WebView(this);WebView rendering=web;web.setBackgroundColor(surface);web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setDomStorageEnabled(false);s.setBlockNetworkLoads(true);s.setSupportZoom(true);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                if(!locked&&r.isForMainFrame())fetchWeb(b,r.getUrl().toString(),v);return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                try{
                    String address=r.getUrl().toString();
                    if(!address.startsWith("https://")&&!address.startsWith("http://"))return blocked();
                    if(url.startsWith("https://")&&address.startsWith("http://"))return blocked();
                    Network.Page p=network.get(b,address,Network.MAX_PAGE);
                    String mime=p.mime.split(";")[0].trim();
                    return new WebResourceResponse(mime,"UTF-8",new ByteArrayInputStream(p.bytes));
                }catch(Exception e){return blocked();}
            }
            private WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
        });
        swipe.addView(web);swipe.setOnChildScrollUpCallback((parent,child)->rendering.canScrollVertically(-1));
        swipe.setOnRefreshListener(()->fetchWeb(b,rendering.getTag()==null?url:rendering.getTag().toString(),rendering));fetchWeb(b,url,rendering);
    }
    private void fetchWeb(Blog b,String url,WebView view){
        try{Blog.requireUrl(url);}catch(Exception e){toast("Поддерживаются только http и https ссылки");if(swipe!=null)swipe.setRefreshing(false);return;}
        int version=screenVersion;swipe.setRefreshing(true);
        workers.execute(()->{try{
            Network.Page page=network.get(b,url,Network.MAX_PAGE);
            if(!page.mime.toLowerCase(Locale.ROOT).contains("html"))throw new IOException("Эта ссылка не ведёт на HTML-страницу");
            runOnUiThread(()->{if(screenVersion==version&&web==view){view.setTag(page.url);view.loadDataWithBaseURL(page.url,page.text(),"text/html","UTF-8",null);swipe.setRefreshing(false);}});
        }catch(Exception e){runOnUiThread(()->{if(screenVersion==version&&web==view){swipe.setRefreshing(false);toast(message(e));}});}});
    }
    private final class RoutedLinks extends LinkMovementMethod {
        private final Blog b;RoutedLinks(Blog b){this.b=b;}
        @Override public boolean onTouchEvent(TextView widget,android.text.Spannable buffer,android.view.MotionEvent event){
            int action=event.getAction();if(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_DOWN){
                int x=(int)event.getX()-widget.getTotalPaddingLeft()+widget.getScrollX();int y=(int)event.getY()-widget.getTotalPaddingTop()+widget.getScrollY();
                android.text.Layout layout=widget.getLayout();if(layout==null)return false;
                int offset=layout.getOffsetForHorizontal(layout.getLineForVertical(y),x);
                android.text.style.URLSpan[] links=buffer.getSpans(offset,offset,android.text.style.URLSpan.class);
                if(links.length>0){if(action==android.view.MotionEvent.ACTION_UP&&!locked)browser(b,links[0].getURL());return true;}
            }return super.onTouchEvent(widget,buffer,event);
        }
    }
    private interface Work{void run()throws Exception;}
    private void mutate(Work work,Runnable done){
        if(busy){toast("Операция уже выполняется");return;}busy=true;subtitle.setText("Отправляем запрос…");
        workers.execute(()->{try{work.run();runOnUiThread(()->{busy=false;toast("Готово");done.run();});}
            catch(Exception e){runOnUiThread(()->{busy=false;subtitle.setText("Не удалось завершить операцию");
                dialog=new AlertDialog.Builder(this).setTitle("Операция не подтверждена").setMessage(message(e)+"\n\nПри обрыве соединения обновите ленту перед повторной отправкой: сервер мог успеть обработать запрос.").setPositiveButton("Понятно",null).show();if(locked)dialog.dismiss();});}});
    }
    private void navigate(Runnable next){
        if(screen.equals("editor")&&draftText!=null&&(!draftText.getText().toString().isBlank()||!draftTitle.getText().toString().isBlank()||photo!=null))
            confirm("Закрыть запись?","Несохранённый текст будет потерян.",next);
        else next.run();
    }
    private void confirm(String title,String message,Runnable ok){dialog=new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Подтвердить",(d,w)->{if(!locked)ok.run();}).show();}
    private boolean save(){try{vault.save();return true;}catch(Exception e){toast(message(e));return false;}}
    private String message(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s;}
    private void toast(String text){if(!isFinishing())Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){
        if(locked)return;
        if(screen.equals("editor")){confirm("Закрыть запись?","Несохранённый текст будет потерян.",()->showFeed("feed",""));}
        else if(!screen.equals("feed"))showFeed("feed","");else super.onBackPressed();
    }
}
