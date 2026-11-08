package com.huandou.app;

/** Builds the JS that overrides JS-readable fingerprint surfaces. Idempotent. */
public final class FpScript {

    private FpScript() {
    }

    private static final String WEBGL =
            "try{"
                    + "if(window.WebGLRenderingContext){"
                    + "var og=WebGLRenderingContext.prototype.getParameter;"
                    + "WebGLRenderingContext.prototype.getParameter=function(n){"
                    + "var q=window.__hdFpProfile;"
                    + "if(q&&n===37445)return q.glVendor;"
                    + "if(q&&n===37446)return q.glRenderer;"
                    + "return og.apply(this,arguments);};}"
                    + "if(window.WebGL2RenderingContext){"
                    + "var og2=WebGL2RenderingContext.prototype.getParameter;"
                    + "WebGL2RenderingContext.prototype.getParameter=function(n){"
                    + "var q=window.__hdFpProfile;"
                    + "if(q&&n===37445)return q.glVendor;"
                    + "if(q&&n===37446)return q.glRenderer;"
                    + "return og2.apply(this,arguments);};}"
                    + "}catch(e){}";

    private static final String TIMEZONE =
            "try{"
                    + "var oo=Date.prototype.getTimezoneOffset;"
                    + "Date.prototype.getTimezoneOffset=function(){"
                    + "var q=window.__hdFpProfile;return q?q.tz:oo.apply(this,arguments);};"
                    + "var ro=Intl.DateTimeFormat.prototype.resolvedOptions;"
                    + "Intl.DateTimeFormat.prototype.resolvedOptions=function(){"
                    + "var r=ro.apply(this,arguments);var q=window.__hdFpProfile;"
                    + "if(q){try{Object.defineProperty(r,'timeZone',"
                    + "{value:q.timezone,configurable:true,enumerable:true});}catch(e){}}"
                    + "return r;};"
                    + "}catch(e){}";

    private static final String CANVAS =
            "if(p.canvas&&!window.__hdCanvasPatched){window.__hdCanvasPatched=true;"
                    + "try{var gi=CanvasRenderingContext2D.prototype.getImageData;"
                    + "CanvasRenderingContext2D.prototype.getImageData=function(x,y,w,h){"
                    + "var d=gi.apply(this,arguments);var q=window.__hdFpProfile;"
                    + "if(q&&q.canvas&&w<=320&&h<=320){try{"
                    + "for(var i=0;i<d.data.length;i+=4){"
                    + "if((i/4+q.seed)%89===0){d.data[i+3]=d.data[i+3]^1;}}"
                    + "}catch(e){}}return d;};}catch(e){}}";

    private static final String APPLY =
            "var d=window.__hdFpDef;"
                    + "d(navigator,'userAgent',function(){return p.ua;});"
                    + "d(navigator,'appVersion',function(){return p.ua.substring(8);});"
                    + "d(navigator,'platform',function(){return p.platform;});"
                    + "d(navigator,'vendor',function(){return p.vendor;});"
                    + "d(navigator,'hardwareConcurrency',function(){return p.cores;});"
                    + "d(navigator,'deviceMemory',function(){return p.mem;});"
                    + "d(navigator,'maxTouchPoints',function(){return p.touch;});"
                    + "d(navigator,'language',function(){return p.lang;});"
                    + "d(navigator,'languages',function(){return [p.lang,'zh','en-US'];});"
                    + "d(navigator,'webdriver',function(){return false;});"
                    + "d(navigator,'plugins',function(){return [];});"
                    + "d(navigator,'mimeTypes',function(){return [];});"
                    + "d(navigator,'doNotTrack',function(){return '1';});"
                    + "d(navigator,'userAgentData',function(){return {"
                    + "brands:[{brand:'Not A(Brand',version:'99'},"
                    + "{brand:'Google Chrome',version:p.chrome},"
                    + "{brand:'Chromium',version:p.chrome}],"
                    + "mobile:p.mobile,platform:p.uaPlatform,"
                    + "getHighEntropyValues:function(h){return Promise.resolve({"
                    + "architecture:p.arch,bitness:p.bitness,model:p.device,"
                    + "platform:p.uaPlatform,platformVersion:p.av,"
                    + "fullVersionList:[{brand:'Google Chrome',version:p.chromeFull}],"
                    + "uaFullVersion:p.chromeFull,wow64:false});}};});"
                    + "d(navigator,'connection',function(){return {"
                    + "effectiveType:'4g',rtt:50,downlink:10,saveData:false,"
                    + "addEventListener:function(){},removeEventListener:function(){}};});"
                    + "d(screen,'width',function(){return p.w;});"
                    + "d(screen,'height',function(){return p.h;});"
                    + "d(screen,'availWidth',function(){return p.w;});"
                    + "d(screen,'availHeight',function(){return p.availH;});"
                    + "d(screen,'colorDepth',function(){return 24;});"
                    + "d(screen,'pixelDepth',function(){return 24;});"
                    + "d(window,'devicePixelRatio',function(){return p.dpr;});";

    public static String build(DeviceProfile p, boolean enabled, boolean canvasNoise) {
        if (!enabled || p == null) {
            return "(function(){try{window.__hdFpProfile=null;}catch(e){}})();";
        }
        return "(function(){"
                + "var p=" + p.jsObject(canvasNoise) + ";"
                + "window.__hdFpProfile=p;"
                + "if(!window.__hdFpInstalled){"
                + "window.__hdFpInstalled=true;"
                + "window.__hdFpDef=function(o,k,g){"
                + "try{Object.defineProperty(o,k,{get:g,configurable:true});}catch(e){}};"
                + WEBGL + TIMEZONE + CANVAS
                + "}"
                + APPLY
                + "})();";
    }

    public static String wrappedForHtml(String script) {
        return "<script>" + script + "</script>";
    }
}
