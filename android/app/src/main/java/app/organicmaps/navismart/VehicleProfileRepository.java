package app.organicmaps.navismart;

import android.content.Context;
import android.content.SharedPreferences;
import com.navismart.economy.ProfileCodec;
import com.navismart.economy.VehicleProfiles.Catalog;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

/** Private local storage, one atomic catalog value. Never clears corrupt data on a failed read. */
public final class VehicleProfileRepository
{
  private static final String KEY = "catalog_v1";
  private final SharedPreferences mPreferences;
  public VehicleProfileRepository(Context context)
  {
    mPreferences = context.getApplicationContext().getSharedPreferences("navismart_vehicles", Context.MODE_PRIVATE);
  }
  public Catalog load() throws IOException
  {
    String text = mPreferences.getString(KEY, null);
    if (text == null) return Catalog.empty();
    if (text.length() > 1_000_000) throw new IOException("Profile catalog is too large");
    try { return new ProfileCodec().read(new StringReader(text)); }
    catch (IllegalArgumentException error) { throw new IOException("Invalid profile catalog", error); }
  }
  public void save(Catalog catalog) throws IOException
  {
    var writer = new StringWriter();
    new ProfileCodec().write(catalog, writer);
    if (writer.getBuffer().length() > 1_000_000) throw new IOException("Profile catalog is too large");
    if (!mPreferences.edit().putString(KEY, writer.toString()).commit())
      throw new IOException("Profile catalog could not be saved");
  }
}
