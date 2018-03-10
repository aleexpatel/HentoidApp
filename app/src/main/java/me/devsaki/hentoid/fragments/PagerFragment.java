package me.devsaki.hentoid.fragments;

import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.ImageButton;

import java.util.List;

import me.devsaki.hentoid.R;
import me.devsaki.hentoid.abstracts.DownloadsFragment;
import me.devsaki.hentoid.adapters.ContentAdapter.ContentsWipedListener;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.util.Helper;
import timber.log.Timber;

/**
 * Created by avluis on 08/26/2016.
 * Presents the list of downloaded works to the user in a classic pager.
 */
public class PagerFragment extends DownloadsFragment implements ContentsWipedListener {

    @Override
    protected void attachScrollListener() {
        mListView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                // Show toolbar:
                if (!override && mAdapter.getItemCount() > 0) {
                    // At top of list
                    if (llm.findViewByPosition(llm.findFirstVisibleItemPosition())
                            .getTop() == 0 && llm.findFirstVisibleItemPosition() == 0) {
                        showToolbar(true, false);
                        if (newContent) {
                            toolTip.setVisibility(View.VISIBLE);
                        }
                    }

                    // Last item in list
                    if (llm.findLastVisibleItemPosition() == mAdapter.getItemCount() - 1) {
                        showToolbar(true, false);
                        if (newContent) {
                            toolTip.setVisibility(View.VISIBLE);
                        }
                    } else {
                        // When scrolling up
                        if (dy < -10) {
                            showToolbar(true, false);
                            if (newContent) {
                                toolTip.setVisibility(View.VISIBLE);
                            }
                            // When scrolling down
                        } else if (dy > 100) {
                            showToolbar(false, false);
                            if (newContent) {
                                toolTip.setVisibility(View.GONE);
                            }
                        }
                    }
                }
            }
        });
    }

    @Override
    protected void checkResults() {
        if (0 == mAdapter.getItemCount())
        {
            if (!isLoaded) update();
            checkContent(true);
        } else {
            if (isLoaded) update();
            checkContent(false);
            mAdapter.setContentsWipedListener(this);
        }

        if (!query.isEmpty()) {
            Timber.d("Saved Query: %s", query);
            if (isLoaded) update();
        }
    }


    @Override
    protected void showToolbar(boolean show, boolean override) {
        this.override = override;

        if (override) {
            if (show) {
                toolbar.setVisibility(View.VISIBLE);
            } else {
                toolbar.setVisibility(View.GONE);
            }
        } else {
            if (show) {
                toolbar.setVisibility(View.VISIBLE);
            } else {
                toolbar.setVisibility(View.GONE);
            }
        }
    }

    @Override
    protected void displayResults(List<Content> results) {
        if (0 == results.size()) {
            Timber.d("Result: Nothing to match.");
            displayNoResults();
        } else {
            toggleUI(SHOW_DEFAULT);

            mAdapter.replaceAll(results);

            toggleUI(SHOW_RESULT);
            updatePager();
        }

/*
        if (query.isEmpty()) {
            if (result != null && !result.isEmpty()) {

                List<Content> singleResult = result;
                mAdapter.replaceAll(singleResult);
                mListView.setAdapter(mAdapter);

                toggleUI(SHOW_RESULT);
                updatePager();
            }
        } else {
            Timber.d("Query: %s", query);
            if (result != null && !result.isEmpty()) {
                Timber.d("Result: Match.");

                List<Content> searchResults = result;
                mAdapter.replaceAll(searchResults);
                mListView.setAdapter(mAdapter);

                toggleUI(SHOW_RESULT);
                showToolbar(true, true);
                updatePager();
            } else {
                Timber.d("Result: Nothing to match.");
                displayNoResults();
            }
        }
        */
    }
}
