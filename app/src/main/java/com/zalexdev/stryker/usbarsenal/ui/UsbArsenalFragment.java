package com.zalexdev.stryker.usbarsenal.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textview.MaterialTextView;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.hid.capability.HidCapabilities;
import com.zalexdev.stryker.hid.capability.HidCapabilityProbe;
import com.zalexdev.stryker.hid.configfs.GadgetFunction;
import com.zalexdev.stryker.hid.configfs.GadgetProfile;
import com.zalexdev.stryker.hid.configfs.GadgetState;
import com.zalexdev.stryker.hid.configfs.UsbGadgetController;
import com.zalexdev.stryker.usbarsenal.GadgetProfileDb;
import com.zalexdev.stryker.utils.Core;

import java.util.Iterator;
import java.util.Locale;

public class UsbArsenalFragment extends Fragment implements GadgetProfileAdapter.Callbacks {

    private Core core;
    private GadgetProfileDb db;
    private UsbGadgetController controller;
    private GadgetProfileAdapter adapter;

    private MaterialTextView stateText;
    private MaterialTextView statePill;
    private MaterialTextView stateDetail;

    private GadgetProfileEditorSheet activeSheet;
    private ActivityResultLauncher<String[]> imagePicker;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_usb_arsenal, container, false);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        imagePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                this::onMassStorageImagePicked);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        core = new Core(requireContext());
        db = new GadgetProfileDb(requireContext());
        controller = new UsbGadgetController(requireContext());

        stateText = v.findViewById(R.id.arsenal_state_text);
        statePill = v.findViewById(R.id.arsenal_state_pill);
        stateDetail = v.findViewById(R.id.arsenal_state_detail);

        SwitchMaterial keepAdbSwitch = v.findViewById(R.id.arsenal_keep_adb_switch);
        keepAdbSwitch.setChecked(core.getBoolean(UsbGadgetController.PREF_KEEP_ADB_TCP));
        keepAdbSwitch.setOnCheckedChangeListener((btn, checked) ->
                core.putBoolean(UsbGadgetController.PREF_KEEP_ADB_TCP, checked));

        RecyclerView list = v.findViewById(R.id.arsenal_profile_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new GadgetProfileAdapter(this);
        list.setAdapter(adapter);

        v.findViewById(R.id.arsenal_restore_btn).setOnClickListener(b -> restoreVendor());
        v.findViewById(R.id.arsenal_new_btn).setOnClickListener(b -> openEditor(null));

        refreshState();
        refreshList();
    }

    private void onMassStorageImagePicked(@Nullable Uri uri) {
        if (activeSheet != null) activeSheet.onImagePicked(uri);
    }

    private void refreshList() {
        adapter.submit(db.listAll());
    }

    private void refreshState() {
        statePill.setText("PROBING");
        statePill.setBackgroundResource(R.drawable.chip_pill_warn);
        statePill.setTextColor(0xFFEF6C00);
        new Thread(() -> {
            HidCapabilities caps = new HidCapabilityProbe(requireContext()).probe();
            GadgetState state = controller.readState();
            requireActivity().runOnUiThread(() -> bindState(caps, state));
        }, "arsenal-probe").start();
    }

    private void bindState(@NonNull HidCapabilities caps, @NonNull GadgetState state) {
        if (!isAdded()) return;
        if (!caps.canManageGadget()) {
            stateText.setText(R.string.arsenal_no_capability);
            statePill.setText("BLOCK");
            statePill.setBackgroundResource(R.drawable.chip_pill_err);
            statePill.setTextColor(0xFFC62828);
        } else if (state.strykerGadgetBound) {
            stateText.setText(getString(R.string.arsenal_state_bound, state.boundUdc));
            statePill.setText("BOUND");
            statePill.setBackgroundResource(R.drawable.chip_pill_ok);
            statePill.setTextColor(0xFF2E7D32);
        } else if (caps.udcName == null) {
            stateText.setText(R.string.arsenal_state_detached);
            statePill.setText("DETACH");
            statePill.setBackgroundResource(R.drawable.chip_pill_err);
            statePill.setTextColor(0xFFC62828);
        } else {
            stateText.setText(R.string.arsenal_state_idle);
            statePill.setText("IDLE");
            statePill.setBackgroundResource(R.drawable.chip_pill_warn);
            statePill.setTextColor(0xFFEF6C00);
        }
        StringBuilder detail = new StringBuilder();
        detail.append("udc=").append(caps.udcName == null ? "—" : caps.udcName)
                .append(" · configfs=").append(caps.configFsMounted ? "✓" : "✗")
                .append(" · stryker_gadget=").append(state.strykerGadgetExists ? "✓" : "✗");
        if (state.exposesHid()) {
            detail.append(" · hidg0=").append(state.hidNodePresent ? "✓" : "✗");
        }
        if (!state.linkedFunctions.isEmpty()) {
            detail.append('\n').append("functions=");
            Iterator<GadgetFunction> it = state.linkedFunctions.iterator();
            while (it.hasNext()) {
                detail.append(it.next().key);
                if (it.hasNext()) detail.append(',');
            }
        }
        if (state.massStorageFile != null && !state.massStorageFile.isEmpty()) {
            detail.append("\nmass_storage=").append(state.massStorageFile);
        }
        stateDetail.setText(detail.toString());
    }

    @Override
    public void onApply(@NonNull GadgetProfile profile) {
        statePill.setText("APPLY");
        statePill.setBackgroundResource(R.drawable.chip_pill_warn);
        statePill.setTextColor(0xFFEF6C00);
        new Thread(() -> {
            boolean teardownOk = controller.teardown();
            boolean ok = controller.apply(profile);
            GadgetState state = controller.readState();
            HidCapabilities caps = new HidCapabilityProbe(requireContext()).probe();
            requireActivity().runOnUiThread(() -> {
                bindState(caps, state);
                if (ok && state.boundUdc != null) {
                    core.toaster(getString(R.string.arsenal_apply_ok, state.boundUdc));
                } else {
                    core.toaster(getString(R.string.arsenal_apply_failed));
                }
            });
        }, "arsenal-apply").start();
    }

    @Override
    public void onEdit(@NonNull GadgetProfile profile) {
        openEditor(profile);
    }

    @Override
    public void onDelete(@NonNull GadgetProfile profile) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.arsenal_delete_profile)
                .setMessage(profile.name)
                .setPositiveButton(R.string.arsenal_delete_profile, (d, w) -> {
                    db.delete(profile.id);
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openEditor(@Nullable GadgetProfile editing) {
        activeSheet = new GadgetProfileEditorSheet(this, editing, built -> {
            db.upsert(built);
            refreshList();
        });
        activeSheet.registerImagePicker(imagePicker);
        activeSheet.show();
    }

    private void restoreVendor() {
        new Thread(() -> {
            controller.unbind();
            controller.teardown();
            GadgetState state = controller.readState();
            HidCapabilities caps = new HidCapabilityProbe(requireContext()).probe();
            requireActivity().runOnUiThread(() -> {
                bindState(caps, state);
                core.toaster(getString(R.string.arsenal_restore_ok));
            });
        }, "arsenal-restore").start();
    }

    @SuppressWarnings("unused")
    private static String compactFunctions(@NonNull GadgetProfile p) {
        StringBuilder sb = new StringBuilder();
        Iterator<GadgetFunction> it = p.functions.iterator();
        while (it.hasNext()) {
            sb.append(it.next().key.toLowerCase(Locale.ROOT));
            if (it.hasNext()) sb.append(' ');
        }
        return sb.toString();
    }
}
