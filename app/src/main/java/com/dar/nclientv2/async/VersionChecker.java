package com.dar.nclientv2.async;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.JsonReader;
import android.util.JsonToken;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.dar.nclientv2.BuildConfig;
import com.dar.nclientv2.R;
import com.dar.nclientv2.settings.Global;
import com.dar.nclientv2.utility.LogUtility;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public class VersionChecker{
    private static final String LATEST_API_URL="https://api.github.com/repos/Dar9586/NClientV2/releases/latest";
    private static final String LATEST_RELEASE_URL="https://github.com/Dar9586/NClientV2/releases/latest";
    private final AppCompatActivity context;
    private String downloadUrl;
    private static String latest=null;
    public VersionChecker(AppCompatActivity context, final boolean silent){
        this.context=context;
        if(latest!=null&&Global.hasStoragePermission(context)){
            downloadVersion(latest);
            latest=null;
            return;
        }
        String versionName= Global.getVersionName(context);
        LogUtility.d("ACTUAL VERSION: "+versionName);
        if(versionName!=null){
            Global.getClient(context).newCall(new Request.Builder().url(LATEST_API_URL).build()).enqueue(new Callback(){
                @Override
                public void onFailure(@NonNull Call call,@NonNull IOException e){
                    context.runOnUiThread(()->{
                        LogUtility.e(e.getLocalizedMessage(),e);
                        if(!silent) Toast.makeText(context, R.string.error_retrieving, Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onResponse(@NonNull Call call,@NonNull  Response response) throws IOException{
                    JsonReader jr=new JsonReader(response.body().charStream());
                    String[]arr=parseVersionJson(jr);
                    jr.close();
                    final String verName = arr[0],body=arr[1];
                    downloadUrl=arr[2];
                    context.runOnUiThread(()->{
                        if(versionName.equals(verName)){
                            if(!silent)
                                Toast.makeText(context, R.string.no_updates_found, Toast.LENGTH_SHORT).show();
                        }else{
                            LogUtility.d("Executing false");
                            createDialog(versionName, verName, body);
                        }
                    });
                }
            });
        }
    }

    private static String[] parseVersionJson(JsonReader jr) throws IOException {
        String[]vars=new String[3];//ver code,body,changelog
        jr.beginObject();
        while(jr.peek()!=JsonToken.END_OBJECT){
            switch (jr.nextName()){
                case "tag_name":vars[0]=jr.nextString();break;
                case "body":vars[1]=jr.nextString();break;
                case "assets":
                    jr.beginArray();
                    while(jr.hasNext()){
                        if(vars[2]!=null){
                            jr.skipValue();
                            continue;
                        }
                        jr.beginObject();
                        while(jr.peek()!=JsonToken.END_OBJECT){
                            if ("browser_download_url".equals(jr.nextName())) {
                                String url=jr.nextString();
                                if(url.contains("Release"))
                                    vars[2] = url;
                            } else {
                                jr.skipValue();
                            }
                        }
                        jr.endObject();
                    }
                    jr.endArray();
                    break;
                default:jr.skipValue();break;
            }
        }
        return vars;
    }

    private void createDialog(String versionName, String latestVersion, String finalBody){
        if(finalBody==null)return;
        finalBody=finalBody
                .replace("\r\n","\n")//Remove ugly newline
                .replace("NClientV2 "+latestVersion,"")//remove version header
                .replaceAll("(\\s*\n\\s*)+","\n")//remove multiple newline
                .replaceAll("\\(.*\\)","").trim();//remove things between ()
        LogUtility.d("Evaluated: "+finalBody);
        LogUtility.d("Creating dialog");
        MaterialAlertDialogBuilder builder=new MaterialAlertDialogBuilder(context);
        LogUtility.d(""+context);
        builder.setTitle(R.string.new_version_found);
        builder.setIcon(R.drawable.ic_file_download);
        builder.setMessage(context.getString(R.string.update_version_format,versionName,latestVersion,finalBody));
        builder.setPositiveButton(R.string.install, (dialog, which) -> {
            if(Global.hasStoragePermission(context)) downloadVersion(latestVersion);
            else{
                latest=latestVersion;
                context.runOnUiThread(()-> context.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,Manifest.permission.READ_EXTERNAL_STORAGE},2));
            }
        }).setNegativeButton(R.string.cancel,null)
                .setNeutralButton(R.string.github, (dialog, which) -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_RELEASE_URL));
            context.startActivity(browserIntent);
        });
        if(!context.isFinishing())builder.show();
    }

    private void downloadVersion(String latestVersion) {
        final File f=new File(Global.UPDATEFOLDER,"NClientV2_"+latestVersion+".apk");
        if(f.exists()){
            if(context.getSharedPreferences("Settings",0).getBoolean("downloaded",false)) {
                installApp(f);
                return;
            }
            f.delete();
        }
        LogUtility.d(f.getAbsolutePath());
        Global.getClient(context).newCall(new Request.Builder().url(downloadUrl).build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                context.runOnUiThread(() -> Toast.makeText(context,R.string.download_update_failed,Toast.LENGTH_LONG).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                context.getSharedPreferences("Settings",0).edit().putBoolean("downloaded",false).apply();
                f.getParentFile().mkdirs();
                f.createNewFile();
                FileOutputStream stream = new FileOutputStream(f);
                InputStream stream1=response.body().byteStream();
                int read;
                byte[] bytes = new byte[1024];
                while ((read = stream1.read(bytes)) != -1) {
                    stream.write(bytes, 0, read);
                }
                stream1.close();
                stream.flush();
                stream.close();
                context.getSharedPreferences("Settings",0).edit().putBoolean("downloaded",true).apply();
                installApp(f);
            }
        });
    }
    private void installApp(File f){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Uri apkUri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", f);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(apkUri);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
        } else {
            Uri apkUri = Uri.fromFile(f);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }
}
