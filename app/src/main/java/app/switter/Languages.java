package app.switter;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;

/** Native app-language settings on Android 13+, a local override on Android 8–12. */
final class Languages {
    private Languages() {}
    static final String[] TAGS={"","en","zh","hi","es","ar","fr","bn","pt","ru","ur","id","de","ja","pcm","ar-EG","mr"};
    private static final String[] NAMES={"","English","中文","हिन्दी","Español","العربية","Français","বাংলা","Português","Русский","اردو","Bahasa Indonesia","Deutsch","日本語","Naijá","العربية (مصر)","मराठी"};
    static String[] names(Context context){String[] names=NAMES.clone();names[0]=context.getString(R.string.system_default);return names;}
    private static String tag(Context context){
        if(Build.VERSION.SDK_INT>=33)return context.getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags();
        return context.getSharedPreferences("language",Context.MODE_PRIVATE).getString("tag","");
    }
    static int selected(Context context){String selected=tag(context);for(int i=0;i<TAGS.length;i++)if(TAGS[i].equals(selected))return i;return 0;}
    static String currentName(Context context){return names(context)[selected(context)];}
    static Context wrap(Context context){
        if(Build.VERSION.SDK_INT>=33)return context;
        String tag=tag(context);if(tag.isEmpty())return context;
        Locale locale=Locale.forLanguageTag(tag);Configuration config=new Configuration(context.getResources().getConfiguration());
        config.setLocales(new LocaleList(locale));config.setLayoutDirection(locale);return context.createConfigurationContext(config);
    }
    static void select(Context context,int index){
        if(Build.VERSION.SDK_INT>=33)context.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(TAGS[index]));
        else context.getSharedPreferences("language",Context.MODE_PRIVATE).edit().putString("tag",TAGS[index]).apply();
    }
}
