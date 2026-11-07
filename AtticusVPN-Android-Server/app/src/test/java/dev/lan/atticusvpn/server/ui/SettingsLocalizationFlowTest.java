/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.app.Application;
import android.app.LocaleManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.LocaleList;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import dev.lan.atticusvpn.server.R;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w320dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SettingsLocalizationFlowTest {
    private Application app(){return RuntimeEnvironment.getApplication();}
    private static <T> List<T> views(View root,Class<T> type) {
        List<T> result=new ArrayList<>();
        if(type.isInstance(root))result.add(type.cast(root));
        if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)result.addAll(views(group.getChildAt(i),type));
        return result;
    }
    private static void layout(View view){
        view.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));
        view.layout(0,0,320,720);
    }
    @Test public void thirteenLocalesResolveRealResourcesAndFormattedMessages() {
        Map<String,String> settings=Map.ofEntries(Map.entry("zh-CN","设置"),Map.entry("zh-TW","設定"),Map.entry("en","Settings"),
                Map.entry("es","Ajustes"),Map.entry("fr","Paramètres"),Map.entry("de","Einstellungen"),
                Map.entry("pt-BR","Configurações"),Map.entry("ru","Настройки"),Map.entry("ar","الإعدادات"),
                Map.entry("hi","सेटिंग"),Map.entry("ja","設定"),Map.entry("ko","설정"),Map.entry("id","Pengaturan"));
        assertEquals(settings.size(),AppLanguage.options().size());
        for(var option:AppLanguage.options()) {
            assertTrue(AppLanguage.select(app(),option.tag()));
            Context localized=AppLanguage.wrap(app());
            assertEquals(option.tag(),settings.get(option.tag()),localized.getString(R.string.settings));
            assertEquals(option.tag().equals("ar")?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR,localized.getResources().getConfiguration().getLayoutDirection());
            String text=localized.getString(R.string.background_status,"notice","battery","background");
            assertTrue(text.contains("notice"));assertTrue(text.contains("battery"));assertTrue(text.contains("background"));
            assertFalse(text.contains("%1$s"));
        }
    }
    @Test public void allLanguageRowsPersistNativeNamedChoicesWithoutServiceCommands() {
        for(var option:AppLanguage.options()) {
            AppLanguage.select(app(),AppLanguage.ENGLISH);
            var controller=Robolectric.buildActivity(LanguageSettingsActivity.class).setup().visible();
            try {
                var activity=controller.get();List<RadioButton> buttons=views(activity.findViewById(android.R.id.content),RadioButton.class);
                assertEquals(13,buttons.size());
                RadioButton chosen=buttons.stream().filter(v->option.tag().equals(v.getTag())).findFirst().orElseThrow();
                assertFalse(chosen.getText().toString().isBlank());chosen.performClick();
                shadowOf(Looper.getMainLooper()).idle();
                assertEquals(option.tag(),AppLanguage.current(app()));
                assertEquals(option.tag(),AppLanguage.preferences(app()).getString("language",""));
                assertNull(shadowOf(app()).getNextStartedService());
            }finally{controller.pause().stop().destroy();}
        }
    }
    @Test @Config(sdk=24) public void legacyAndroidRestoresIndonesianAndArabicWithoutLocaleManager() {
        Map<String,String> labels=Map.of("id","Pengaturan","ar","الإعدادات","zh-TW","設定");
        for(String tag:List.of("id","ar","zh-TW")) {
            assertTrue(AppLanguage.select(app(),tag));assertEquals(tag,AppLanguage.current(app()));
            assertEquals(tag,AppLanguage.normalize(AppLanguage.wrap(app()).getResources().getConfiguration().getLocales().get(0).toLanguageTag()));
            assertEquals(labels.get(tag),AppLanguage.wrap(app()).getString(R.string.settings));
        }
    }
    @Test public void systemLocaleSelectionPreservesScriptAndNormalizesRegionalAliases() {
        LocaleManager manager=app().getSystemService(LocaleManager.class);
        for(var sample:Map.of("fr-CA","fr","pt-PT","pt-BR","id-ID","id","ar-EG","ar","zh-Hant-HK","zh-TW").entrySet()) {
            manager.setApplicationLocales(LocaleList.forLanguageTags(sample.getKey()));
            assertEquals(sample.getValue(),AppLanguage.current(app()));
        }
        assertEquals("id",AppLanguage.normalize("in-ID"));assertEquals("en",AppLanguage.normalize("invalid"));
    }
    @Test public void everySettingsEntryHasLeadingIconAndKeepsItsDestination() {
        AppLanguage.select(app(),"en");AppAppearance.select(app(),AppAppearance.LIGHT);
        var controller=Robolectric.buildActivity(SettingsActivity.class).setup().visible();
        try {
            var activity=controller.get();View root=activity.findViewById(android.R.id.content);layout(root);
            var buttons=views(root,Button.class);assertEquals(6,buttons.size());
            Class<?>[] destinations={VpnSettingsActivity.class,BackgroundAccessActivity.class,LanguageSettingsActivity.class,
                    AppearanceSettingsActivity.class,DeviceCapabilitiesActivity.class,RuntimeLogActivity.class};
            for(int i=0;i<buttons.size();i++) {
                Button button=buttons.get(i);assertNotNull(button.getCompoundDrawablesRelative()[0]);
                assertEquals(22,button.getCompoundDrawablesRelative()[0].getBounds().width());
                assertTrue(button.getHeight()>=48);assertTrue(button.getWidth()<=root.getWidth());
                button.performClick();assertEquals(destinations[i].getName(),shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            }
            assertNull(shadowOf(app()).getNextStartedService());
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void narrowRtlAndLargeTextSettingsRemainReadableAndRenderPreviews()throws Exception {
        for(boolean dark:List.of(false,true))for(String tag:List.of("zh-CN","de","ar","hi")) {
            AppLanguage.select(app(),tag);AppAppearance.select(app(),dark?AppAppearance.DARK:AppAppearance.LIGHT);
            var controller=Robolectric.buildActivity(SettingsActivity.class).setup().visible();
            try {
                var activity=controller.get();View root=activity.findViewById(android.R.id.content);
                for(Button button:views(root,Button.class))button.setTextSize(22);
                layout(root);
                for(Button button:views(root,Button.class)) {
                    assertNotNull(button.getLayout());
                    assertEquals(0,button.getLayout().getEllipsisCount(button.getLayout().getLineCount()-1));
                    assertTrue(button.getHeight()>=button.getLayout().getHeight()+button.getCompoundPaddingTop()+button.getCompoundPaddingBottom());
                    assertEquals(tag.equals("ar")?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR,button.getLayoutDirection());
                    assertNotNull(button.getCompoundDrawablesRelative()[0]);
                }
                String directory=System.getProperty("atticus.glass.previewDir");
                if(directory!=null) {
                    File target=new File(directory,"settings-"+tag+(dark?"-dark":"-light")+".png");target.getParentFile().mkdirs();
                    Bitmap bitmap=Bitmap.createBitmap(320,720,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
                    try(FileOutputStream out=new FileOutputStream(target)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
                }
            }finally{controller.pause().stop().destroy();}
        }
    }
}
