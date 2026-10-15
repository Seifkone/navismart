package app.organicmaps.navismart;

import android.content.Context;
import android.util.AtomicFile;
import com.navismart.toll.TollCatalog;
import com.navismart.toll.TollGateHints;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.json.*;

/** Static data files from the fork. No remote routing API; every replacement is checked atomically. */
public final class TollRepository {
  private static final int MAGIC=0x4e535433;
  private static final Object CACHE_LOCK=new Object();
  private static final String ROOT="https://raw.githubusercontent.com/Seifkone/navismart/navismart/android/app/src/main/assets/navismart-tolls/";
  private final Context context;
  private final AtomicFile file;
  public record Snapshot(Instant receivedAt,List<TollCatalog> catalogs,List<TollGateHints.Location> locations) {
    public Snapshot { catalogs=List.copyOf(catalogs); locations=List.copyOf(locations); }
    public boolean fresh(Instant now) {
      return !now.isBefore(receivedAt) && Duration.between(receivedAt,now).compareTo(Duration.ofDays(7))<=0
          && catalogs.stream().allMatch(c->!now.isBefore(c.source().receivedAt()) && Duration.between(c.source().receivedAt(),now).compareTo(Duration.ofDays(7))<=0);
    }
  }
  private record Download(TollCatalog.Source source,byte[] bytes) {}
  private record Pack(Snapshot snapshot,List<Download> downloads,byte[] geography) {}
  public TollRepository(Context context) {
    this.context=context.getApplicationContext(); file=new AtomicFile(new File(context.getFilesDir(),"navismart-tolls-v3.bin"));
  }
  public Snapshot load() throws IOException {
    synchronized(CACHE_LOCK){return readCache();}
  }
  public Snapshot bundled() throws IOException {
    try(var in=context.getAssets().open("navismart-tolls/manifest.json")) { return parse(read(in,100_000),false,Instant.now(),null).snapshot(); }
  }
  private Snapshot readCache() throws IOException {
    if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath()+".bak").exists()) {
      return bundled();
    }
    try(var in=new DataInputStream(file.openRead())) {
      if(in.readInt()!=MAGIC)throw new IOException("Unsupported tariff cache");
      Instant received=Instant.ofEpochMilli(in.readLong()); int count=in.readInt();
      if(count<1 || count>20)throw new IOException("Invalid cache count");
      var catalogs=new ArrayList<TollCatalog>(); var ids=new HashSet<String>(); long total=0;
      for(int i=0;i<count;i++) {
        String id=in.readUTF(); if(!id.matches("[A-Z0-9_]{1,30}") || !ids.add(id))throw new IOException("Invalid network");
        var source=new TollCatalog.Source(id,in.readUTF(),LocalDate.parse(in.readUTF()),Instant.ofEpochMilli(in.readLong()));
        String checksum=in.readUTF();int length=in.readInt(); total+=length;
        if(length<=0 || length>8_000_000 || total>25_000_000)throw new IOException("Oversized cache");
        byte[] bytes=new byte[length]; in.readFully(bytes);if(!sha256(bytes).equals(checksum))throw new IOException("Damaged tariff cache");
        catalogs.add(TollCatalog.read(new ByteArrayInputStream(bytes),source));
      }
      String geoChecksum=in.readUTF();int geoLength=in.readInt(); if(geoLength<=0 || geoLength>1_000_000)throw new IOException("Invalid geography cache");
      byte[] geo=new byte[geoLength];in.readFully(geo);if(!sha256(geo).equals(geoChecksum))throw new IOException("Damaged geography cache");
      var locations=TollGateHints.read(new InputStreamReader(new ByteArrayInputStream(geo),StandardCharsets.UTF_8));
      if(in.read()!=-1)throw new IOException("Trailing cache data"); return new Snapshot(received,catalogs,locations);
    }catch(IllegalArgumentException e){throw new IOException("Invalid tariff cache",e);}
  }
  public Snapshot refresh(Instant now)throws IOException {
    synchronized(CACHE_LOCK){return refreshLocked(now);}
  }
  private Snapshot refreshLocked(Instant now)throws IOException {
    var prefs=context.getSharedPreferences("navismart_tolls",0); long previous=prefs.getLong("attempt",0);
    if(previous>0 && now.toEpochMilli()>=previous && now.toEpochMilli()-previous<300_000)throw new IOException("Tariff backoff");
    prefs.edit().putLong("attempt",now.toEpochMilli()).apply(); Snapshot old=null;
    try{old=load();}catch(IOException ignored){}
    var pack=parse(download(ROOT+"manifest.json",100_000),true,now,old); FileOutputStream output=null;
    try {
      output=file.startWrite(); var out=new DataOutputStream(output);
      out.writeInt(MAGIC);out.writeLong(now.toEpochMilli());out.writeInt(pack.downloads().size());
      for(var d:pack.downloads()) {
        out.writeUTF(d.source().network());out.writeUTF(d.source().url());out.writeUTF(d.source().effectiveFrom().toString());
        out.writeLong(d.source().receivedAt().toEpochMilli());out.writeUTF(sha256(d.bytes()));out.writeInt(d.bytes().length);out.write(d.bytes());
      }
      out.writeUTF(sha256(pack.geography()));out.writeInt(pack.geography().length);out.write(pack.geography());
      out.flush();file.finishWrite(output);return pack.snapshot();
    }catch(IOException e){if(output!=null)file.failWrite(output);throw e;}
  }
  private Pack parse(byte[] manifest,boolean remote,Instant now,Snapshot previous)throws IOException {
    try {
      var root=new JSONObject(new String(manifest,StandardCharsets.UTF_8));
      if(root.getInt("schema")!=1)throw new IOException("Unsupported manifest");
      Instant checked=Instant.parse(root.getString("checkedAt"));
      if(checked.isAfter(now.plusSeconds(300)))throw new IOException("Future publication");
      var networks=root.getJSONArray("networks");if(networks.length()<1 || networks.length()>20)throw new IOException("Invalid network count");
      var downloads=new ArrayList<Download>();var catalogs=new ArrayList<TollCatalog>();var ids=new HashSet<String>();long total=0;
      for(int i=0;i<networks.length();i++) {
        var item=networks.getJSONObject(i);String id=item.getString("id"),name=item.getString("file");
        if(!id.matches("[A-Z0-9_]{1,30}") || !ids.add(id) || !name.equals(id+".csv"))throw new IOException("Invalid file name");
        String url=item.getString("sourceUrl");
        if(!url.startsWith("https://voyage.aprr.fr/sites/default/files/") && !url.startsWith("https://public-content.vinci-autoroutes.com/PDF/")
            && !url.startsWith("https://www.autoroutes.sanef.com/sites/default/files/"))throw new IOException("Unexpected operator source");
        LocalDate effective=LocalDate.parse(item.getString("effectiveFrom"));
        if(effective.isAfter(now.atZone(ZoneId.of("Europe/Paris")).toLocalDate()))throw new IOException("Future tariff");
        if(previous!=null)for(var old:previous.catalogs())if(old.source().network().equals(id)
            && (effective.isBefore(old.source().effectiveFrom()) || checked.isBefore(old.source().receivedAt())))throw new IOException("Tariff downgrade");
        byte[] bytes;
        if(remote)bytes=download(ROOT+name,8_000_000);
        else try(var in=context.getAssets().open("navismart-tolls/"+name)){bytes=read(in,8_000_000);}
        total+=bytes.length;if(total>25_000_000)throw new IOException("Oversized pack");
        if(!sha256(bytes).equalsIgnoreCase(item.getString("sha256")))throw new IOException("Tariff digest mismatch");
        var source=new TollCatalog.Source(id,url,effective,checked);var catalog=TollCatalog.read(new ByteArrayInputStream(bytes),source);
        if(catalog.pairCount()!=item.getInt("pairs") || catalog.gates().size()!=item.getInt("gates"))throw new IOException("Incomplete catalog");
        downloads.add(new Download(source,bytes));catalogs.add(catalog);
      }
      if(previous!=null)for(var old:previous.catalogs())if(!ids.contains(old.source().network()))throw new IOException("Coverage regression");
      var geoInfo=root.getJSONObject("geography");
      if(!geoInfo.getString("file").equals("gates.csv"))throw new IOException("Unexpected geography file");
      byte[] geography;
      if(remote)geography=download(ROOT+"gates.csv",1_000_000);
      else try(var in=context.getAssets().open("navismart-tolls/gates.csv")){geography=read(in,1_000_000);}
      if(!sha256(geography).equalsIgnoreCase(geoInfo.getString("sha256")))throw new IOException("Geography digest mismatch");
      var locations=TollGateHints.read(new InputStreamReader(new ByteArrayInputStream(geography),StandardCharsets.UTF_8));
      if(locations.size()!=geoInfo.getInt("locations"))throw new IOException("Incomplete geography");
      return new Pack(new Snapshot(now,catalogs,locations),List.copyOf(downloads),geography);
    }catch(JSONException | IllegalArgumentException e){throw new IOException("Invalid tariff manifest",e);}
  }
  private static String sha256(byte[] bytes) {
    try {var text=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
  }
  private static byte[] read(InputStream in,int limit)throws IOException {
    var out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int count;
    while((count=in.read(buffer))!=-1){if(out.size()+count>limit)throw new IOException("Oversized asset");out.write(buffer,0,count);}
    return out.toByteArray();
  }
  private static byte[] download(String url,int limit)throws IOException {
    var connection=(HttpURLConnection)new URL(url).openConnection();connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(15_000);connection.setReadTimeout(20_000);connection.setRequestProperty("User-Agent","NaviSmart/0.4");
    try {
      if(connection.getResponseCode()!=200 || connection.getContentLengthLong()>limit)throw new IOException("Download unavailable or oversized");
      try(var in=connection.getInputStream();var out=new ByteArrayOutputStream()) {
        byte[] buffer=new byte[16384];int n;long deadline=System.nanoTime()+90_000_000_000L;
        while((n=in.read(buffer))!=-1) {
          if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline || out.size()+n>limit)throw new IOException("Download interrupted or oversized");
          out.write(buffer,0,n);
        }return out.toByteArray();
      }
    }finally{connection.disconnect();}
  }
}
