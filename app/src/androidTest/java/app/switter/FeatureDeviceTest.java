package app.switter;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import okhttp3.mockwebserver.*;

@RunWith(AndroidJUnit4.class)
public class FeatureDeviceTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();
    private Activity activity;
    private String backup,oldLocale;
    @Before public void prepare(){
        backup=context.getSharedPreferences("vault",0).getString("data",null);
        oldLocale=Build.VERSION.SDK_INT>=33?context.getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags():context.getSharedPreferences("language",0).getString("tag","");
        context.getSharedPreferences("vault",0).edit().clear().commit();Languages.select(context,9);
        instrumentation.waitForIdleSync();
    }
    @After public void restore(){
        finish();
        if(backup==null)context.getSharedPreferences("vault",0).edit().clear().commit();else context.getSharedPreferences("vault",0).edit().putString("data",backup).commit();
        if(Build.VERSION.SDK_INT>=33)context.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(oldLocale));
        else context.getSharedPreferences("language",0).edit().putString("tag",oldLocale).commit();
        instrumentation.waitForIdleSync();
    }
    private void launch(){activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();}
    private void finish(){if(activity!=null){Activity a=activity;instrumentation.runOnMainSync(a::finish);instrumentation.waitForIdleSync();activity=null;}}
    private void call(String name,Class<?>[] types,Object... args){instrumentation.runOnMainSync(()->{try{
        java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(activity,args);
    }catch(Exception e){throw new RuntimeException(e);}});instrumentation.waitForIdleSync();}
    private View find(View view,String text){
        if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),text);if(found!=null)return found;}}return null;
    }
    private EditText findQuery(View view){if(view instanceof EditText)return (EditText)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText found=findQuery(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private Switch findSwitch(View view){if(view instanceof Switch)return (Switch)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Switch found=findSwitch(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private View find(String text){return find(activity.getWindow().getDecorView(),text);}
    private void waitFor(String text)throws Exception{long end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end&&find(text)==null)Thread.sleep(50);instrumentation.waitForIdleSync();assertNotNull(text,find(text));}
    private void screenshot(String name)throws Exception{Thread.sleep(250);instrumentation.waitForIdleSync();instrumentation.runOnMainSync(()->{try{
        View view=activity.getWindow().getDecorView();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
    }catch(Exception e){throw new RuntimeException(e);}});}
    @Test public void subscriptionArchiveAndSearchWithPartialFailure()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
                String path=request.getPath();
                if(path.equals("/follow/posts.json"))return new MockResponse().setBody("[{\"id\":\"live\",\"title\":\"Live needle\",\"content\":\"Current text\"}]");
                if(path.equals("/follow/archive/"))return new MockResponse().setBody("<a href='2026.html'>Archive</a><a href='broken.html'>Broken</a>");
                if(path.equals("/follow/archive/2026.html"))return new MockResponse().setBody("<h1>Архив записей</h1><article class='post'><h2>Archived needle</h2><p><b>Full archive text</b></p></article>");
                return new MockResponse().setResponseCode(500);
            }});
            Vault vault=new Vault(context);Blog follow=new Blog();follow.name="Test subscription";follow.url=server.url("/follow/").toString();vault.blogs.add(follow);vault.save();launch();waitFor("Live needle");screenshot("feature-feed");
            call("showFeed",new Class<?>[]{String.class,String.class},"blog",follow.id);assertNotNull(find("Архив"));
            instrumentation.runOnMainSync(()->find("Архив").performClick());waitFor("2026.html");
            instrumentation.runOnMainSync(()->find("2026.html").performClick());waitFor("Archived needle");assertNull(find("Удалить"));assertNotNull(find("↗ Репост"));screenshot("feature-archive");
            call("showSearch",new Class<?>[]{});
            instrumentation.runOnMainSync(()->{findQuery(activity.getWindow().getDecorView()).setText("needle");find("Найти").performClick();});waitFor("Найдено записей: 1");assertNull(find("Archived needle"));
            instrumentation.runOnMainSync(()->{findSwitch(activity.getWindow().getDecorView()).setChecked(true);find("Найти").performClick();});waitFor("Найдено записей: 2");assertNotNull(find("Archived needle"));assertNotNull(find("Test subscription · broken.html: HTTP 500"));screenshot("feature-search");
            call("startSearch",new Class<?>[]{String.class,boolean.class},"missing query",true);waitFor("Ничего не найдено");
        }
    }
    @Test public void everyLanguageLoadsAndFormatsAndPersists()throws Exception{
        String[] feed={"Feed","动态","फ़ीड","Inicio","الموجز","Fil","ফিড","Feed","Лента","فیڈ","Linimasa","Feed","フィード","Feed","المنشورات","फीड"};
        assertEquals(16,Languages.TAGS.length-1);
        for(int index=1;index<Languages.TAGS.length;index++){
            Languages.select(context,index);instrumentation.waitForIdleSync();launch();
            assertEquals(feed[index-1],activity.getString(R.string.feed));assertEquals(index,Languages.selected(activity));assertNotNull(find(feed[index-1]));
            assertTrue(activity.getString(R.string.search_count,42).contains(String.format(activity.getResources().getConfiguration().getLocales().get(0),"%d",42)));
            int expected=(Languages.TAGS[index].startsWith("ar")||Languages.TAGS[index].equals("ur"))?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR;
            assertEquals(expected,activity.getResources().getConfiguration().getLayoutDirection());
            call("settings",new Class<?>[]{});assertNotNull(find(activity.getString(R.string.language)));
            if(index==1||index==5||index==9||index==10||index==3)screenshot("feature-language-"+Languages.TAGS[index]);
            finish();launch();assertEquals(index,Languages.selected(activity));finish();
        }
        Languages.select(context,0);instrumentation.waitForIdleSync();assertEquals(0,Languages.selected(context));
    }
}
