package app.organicmaps.navismart;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.SharedPreferences;
import com.navismart.economy.Estimate;
import com.navismart.economy.TripModel.*;
import com.navismart.economy.VehicleProfiles.*;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class VehicleProfileRepositoryTest
{
  private SharedPreferences mPreferences;
  private SharedPreferences.Editor mEditor;
  private VehicleProfileRepository mRepository;
  @Before public void setUp()
  {
    Context context = mock(Context.class);
    when(context.getApplicationContext()).thenReturn(context);
    mPreferences = mock(SharedPreferences.class);
    mEditor = mock(SharedPreferences.Editor.class);
    when(context.getSharedPreferences("navismart_vehicles", Context.MODE_PRIVATE)).thenReturn(mPreferences);
    when(mPreferences.edit()).thenReturn(mEditor);
    when(mEditor.putString(anyString(), anyString())).thenReturn(mEditor);
    when(mEditor.commit()).thenReturn(true);
    mRepository = new VehicleProfileRepository(context);
  }
  @Test public void missingCatalogReturnsEmptyWithoutWriting() throws Exception
  {
    assertEquals(Catalog.empty(), mRepository.load());
    verify(mPreferences, never()).edit();
  }
  @Test public void saveThenReloadPreservesCatalog() throws Exception
  {
    var catalog = catalog();
    mRepository.save(catalog);
    var serialized = ArgumentCaptor.forClass(String.class);
    verify(mEditor).putString(eq("catalog_v1"), serialized.capture());
    when(mPreferences.getString("catalog_v1", null)).thenReturn(serialized.getValue());
    assertEquals(catalog, mRepository.load());
    verify(mEditor).commit();
  }
  @Test public void corruptCatalogDoesNotOverwriteStorage()
  {
    when(mPreferences.getString("catalog_v1", null)).thenReturn("format.version=99\nprofiles.count=0\n");
    assertThrows(IOException.class, () -> mRepository.load());
    verify(mPreferences, never()).edit();
  }
  @Test public void incompatibleStorageTypeIsReported()
  {
    when(mPreferences.getString("catalog_v1", null)).thenThrow(new ClassCastException());
    assertThrows(IOException.class, () -> mRepository.load());
    verify(mPreferences, never()).edit();
  }
  @Test public void failedCommitIsReported()
  {
    when(mEditor.commit()).thenReturn(false);
    assertThrows(IOException.class, () -> mRepository.save(catalog()));
  }
  private static Catalog catalog()
  {
    return Catalog.empty().put(new Profile(new Vehicle("rio", "Kia Rio — non calibrée", Powertrain.FLEXFUEL,
        List.of(new Store("tank", Energy.E85, null, null, null, Estimate.missing(Estimate.State.UNKNOWN, "not measured"),
            Estimate.missing(Estimate.State.UNKNOWN, "not supplied")))), new Policy(5, 0.5, 0.25),
        Characteristics.unknown(), Instant.parse("2026-10-13T12:00:00Z")));
  }
}
