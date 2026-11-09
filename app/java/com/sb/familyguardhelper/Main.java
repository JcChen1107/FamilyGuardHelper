package com.sb.familyguardhelper;

import android.app.Application;

/** 空入口类：模块本体不运行逻辑，hook 全部在 HookEntry 中 */
public class Main extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
    }
}
