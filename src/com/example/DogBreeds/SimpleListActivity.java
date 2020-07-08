package com.jstappdev.dbclf;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class SimpleListActivity extends Activity implements View.OnClickListener {

    private final List<String> translations = Arrays.asList("en", "de", "fr");

    private ExpandableListView expListView;
    private List<String> listDataHeader;
    private HashMap<String, String> listDataChild;
    private ArrayList<String> recogs;
    private HashMap<String, Integer> mapIndex;  // hashmap for side index
    private static String wikiLangSubDomain = "";

    // create list for side index
    private void getIndexList() {
        mapIndex = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < listDataHeader.size(); i++) {
            String item = listDataHeader.get(i);
            String index = item.substring(0, 1);

            if (mapIndex.get(index) == null)
                mapIndex.put(index, i);
        }
    }

    // display side index
    @SuppressLint("InflateParams")
    private void displayIndex() {
        LinearLayout indexLayout = findViewById(R.id.side_index);

        TextView textView;
        List<String> indexList = new ArrayList<String>(mapIndex.keySet());
        for (String index : indexList) {
            textView = (TextView) getLayoutInflater().inflate(
                    R.layout.side_index_item, null, false);
            textView.setText(index);
            textView.setOnClickListener(this);
            indexLayout.addView(textView);
        }

        indexLayout.setVisibility(View.VISIBLE);
    }

    // handle click on side index
    @Override
    public void onClick(View v) {
        TextView selectedIndex = (TextView) v;
        expListView.setSelection(mapIndex.get(selectedIndex.getText().toString()));
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        String lang = Locale.getDefault().getLanguage();
        if (translations.contains(lang)) {
            wikiLangSubDomain = lang + ".";
        }

        final Intent intent = getIntent();
        recogs = intent.getStringArrayListExtra("recogs");

        expListView = findViewById(R.id.lvExp);

        prepareListData();

        ExpandableListAdapter listAdapter = new ListAdapter(this, listDataHeader, listDataChild);

        expListView.setOnChildClickListener((parent, v, groupPosition, childPosition, id) -> {
            final String title = listDataHeader.get(groupPosition);
            final String searchText = title.replace(" ", "+");

            DialogInterface.OnClickListener dialogClickListener = (dialog, which) -> {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        final String url = String.format("https://%swikipedia.org/w/index.php?search=%s&title=Special:Search", wikiLangSubDomain, searchText);

                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(url));
                        startActivity(i);
                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        break;
                }
            };

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.searchFor).setTitle(title)
                    .setNegativeButton(R.string.no, dialogClickListener)
                    .setPositiveButton(R.string.yes, dialogClickListener).show();

            return false;
        });

        expListView.setAdapter(listAdapter);

        // display list side index if we haven't got a list of current recognitions
        if (null == recogs) {
            getIndexList();
            displayIndex();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (null != recogs)
            for (int i = 0; i < listDataHeader.size(); i++)
                expListView.expandGroup(i);
    }

    /*
     * Preparing the list data
     */
    private void prepareListData() {
        listDataHeader = new ArrayList<>();
        listDataChild = new HashMap<>();

        Collections.addAll(listDataHeader, getResources().getStringArray(R.array.breeds_array));
        String[] fileNames = getResources().getStringArray(R.array.file_names);

        // load file names
        for (int i = 0; i < listDataHeader.size(); i++) {
            listDataChild.put(listDataHeader.get(i), fileNames[i]);
        }

        if (null != recogs) {
            listDataHeader = new ArrayList<>();
            listDataHeader.addAll(recogs);
        } else {
            Collator coll = Collator.getInstance(Locale.getDefault());
            coll.setStrength(Collator.PRIMARY);
            Collections.sort(listDataHeader, coll);
        }
    }

}
