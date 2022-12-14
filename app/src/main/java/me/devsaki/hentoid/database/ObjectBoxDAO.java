package me.devsaki.hentoid.database;

import android.content.Context;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.paging.DataSource;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;

import com.annimon.stream.Optional;
import com.annimon.stream.Stream;
import com.annimon.stream.function.Consumer;

import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import io.objectbox.BoxStore;
import io.objectbox.android.ObjectBoxDataSource;
import io.objectbox.android.ObjectBoxLiveData;
import io.objectbox.query.Query;
import io.objectbox.relation.ToOne;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import me.devsaki.hentoid.database.domains.Attribute;
import me.devsaki.hentoid.database.domains.Chapter;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.ErrorRecord;
import me.devsaki.hentoid.database.domains.Group;
import me.devsaki.hentoid.database.domains.GroupItem;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.database.domains.QueueRecord;
import me.devsaki.hentoid.database.domains.RenamingRule;
import me.devsaki.hentoid.database.domains.SearchRecord;
import me.devsaki.hentoid.database.domains.SiteBookmark;
import me.devsaki.hentoid.database.domains.SiteHistory;
import me.devsaki.hentoid.enums.AttributeType;
import me.devsaki.hentoid.enums.Grouping;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.util.ContentHelper;
import me.devsaki.hentoid.util.Helper;
import me.devsaki.hentoid.util.Preferences;
import me.devsaki.hentoid.util.SearchHelper;
import me.devsaki.hentoid.util.StringHelper;
import me.devsaki.hentoid.widget.ContentSearchManager;
import timber.log.Timber;

public class ObjectBoxDAO implements CollectionDAO {

    private final ObjectBoxDB db;

    ObjectBoxDAO(ObjectBoxDB db) {
        this.db = db;
    }

    public ObjectBoxDAO(Context ctx) {
        db = ObjectBoxDB.getInstance(ctx);
    }

    // Use for testing (store generated by the test framework)
    public ObjectBoxDAO(BoxStore store) {
        db = ObjectBoxDB.getInstance(store);
    }


    public void cleanup() {
        db.closeThreadResources();
    }

    @Override
    public long getDbSizeBytes() {
        return db.getDbSizeBytes();
    }

    @Override
    public List<Long> selectStoredContentIds(boolean nonFavouritesOnly, boolean includeQueued, int orderField, boolean orderDesc) {
        return Helper.getListFromPrimitiveArray(db.selectStoredContentQ(nonFavouritesOnly, includeQueued, orderField, orderDesc).build().findIds());
    }

    @Override
    public long countStoredContent(boolean nonFavouritesOnly, boolean includeQueued) {
        return db.selectStoredContentQ(nonFavouritesOnly, includeQueued, -1, false).build().count();
    }

    @Override
    public long countContentWithUnhashedCovers() {
        return db.selectNonHashedContent().count();
    }

    @Override
    public List<Content> selectContentWithUnhashedCovers() {
        return db.selectNonHashedContent().find();
    }

    @Override
    public void streamStoredContent(boolean nonFavouritesOnly, boolean includeQueued, int orderField, boolean orderDesc, Consumer<Content> consumer) {
        Query<Content> query = db.selectStoredContentQ(nonFavouritesOnly, includeQueued, orderField, orderDesc).build();
        query.forEach(consumer::accept);
    }

    @Override
    public Single<List<Long>> selectRecentBookIds(ContentSearchManager.ContentSearchBundle searchBundle) {
        return Single.fromCallable(() -> contentIdSearch(false, searchBundle, Collections.emptyList()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<List<Long>> searchBookIds(ContentSearchManager.ContentSearchBundle searchBundle, List<Attribute> metadata) {
        return Single.fromCallable(() -> contentIdSearch(false, searchBundle, metadata))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<List<Long>> searchBookIdsUniversal(ContentSearchManager.ContentSearchBundle searchBundle) {
        return
                Single.fromCallable(() -> contentIdSearch(true, searchBundle, Collections.emptyList()))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public long insertAttribute(@NonNull Attribute attr) {
        return db.insertAttribute(attr);
    }

    @Override
    @Nullable
    public Attribute selectAttribute(long id) {
        return db.selectAttribute(id);
    }

    @Override
    public Single<SearchHelper.AttributeQueryResult> selectAttributeMasterDataPaged(
            @NonNull List<AttributeType> types,
            String filter,
            long groupId,
            List<Attribute> attrs,
            @ContentHelper.Location int location,
            @ContentHelper.Type int contentType,
            boolean includeFreeAttrs,
            int page,
            int booksPerPage,
            int orderStyle) {
        return Single
                .fromCallable(() -> pagedAttributeSearch(types, filter, groupId, attrs, location, contentType, includeFreeAttrs, orderStyle, page, booksPerPage))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<SparseIntArray> countAttributesPerType(
            long groupId,
            List<Attribute> filter,
            @ContentHelper.Location int location,
            @ContentHelper.Type int contentType) {
        return Single.fromCallable(() -> countAttributes(groupId, filter, location, contentType))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public List<Chapter> selectChapters(long contentId) {
        return db.selectChapters(contentId);
    }

    public LiveData<List<Content>> selectErrorContentLive() {
        return new ObjectBoxLiveData<>(db.selectErrorContentQ());
    }

    public LiveData<List<Content>> selectErrorContentLive(String query) {
        ContentSearchManager.ContentSearchBundle bundle = new ContentSearchManager.ContentSearchBundle();
        bundle.setQuery(query);
        bundle.setSortField(Preferences.Constant.ORDER_FIELD_DOWNLOAD_PROCESSING_DATE);
        return new ObjectBoxLiveData<>(db.selectContentUniversalQ(bundle, new int[]{StatusContent.ERROR.getCode()}));
    }

    public List<Content> selectErrorContent() {
        return db.selectErrorContentQ().find();
    }

    public LiveData<Integer> countAllBooksLive() {
        // This is not optimal because it fetches all the content and returns its size only
        // That's because ObjectBox v2.4.0 does not allow watching Query.count or Query.findLazy using LiveData, but only Query.find
        // See https://github.com/objectbox/objectbox-java/issues/776
        ObjectBoxLiveData<Content> livedata = new ObjectBoxLiveData<>(db.selectVisibleContentQ());

        MediatorLiveData<Integer> result = new MediatorLiveData<>();
        result.addSource(livedata, v -> result.setValue(v.size()));
        return result;
    }

    public LiveData<Integer> countBooks(
            long groupId,
            List<Attribute> metadata,
            @ContentHelper.Location int location,
            @ContentHelper.Type int contentType) {
        // This is not optimal because it fetches all the content and returns its size only
        // That's because ObjectBox v2.4.0 does not allow watching Query.count or Query.findLazy using LiveData, but only Query.find
        // See https://github.com/objectbox/objectbox-java/issues/776
        ContentSearchManager.ContentSearchBundle bundle = new ContentSearchManager.ContentSearchBundle();
        bundle.setGroupId(groupId);
        bundle.setLocation(location);
        bundle.setContentType(contentType);
        bundle.setSortField(Preferences.Constant.ORDER_FIELD_NONE);
        ObjectBoxLiveData<Content> livedata = new ObjectBoxLiveData<>(db.selectContentSearchContentQ(bundle, metadata));

        MediatorLiveData<Integer> result = new MediatorLiveData<>();
        result.addSource(livedata, v -> result.setValue(v.size()));
        return result;
    }

    @Override
    public LiveData<PagedList<Content>> selectRecentBooks(ContentSearchManager.ContentSearchBundle searchBundle) {
        return getPagedContent(false, searchBundle, Collections.emptyList());
    }

    @Override
    public LiveData<PagedList<Content>> searchBooks(ContentSearchManager.ContentSearchBundle searchBundle, List<Attribute> metadata) {
        return getPagedContent(false, searchBundle, metadata);
    }

    @Override
    public LiveData<PagedList<Content>> searchBooksUniversal(ContentSearchManager.ContentSearchBundle searchBundle) {
        return getPagedContent(true, searchBundle, Collections.emptyList());
    }

    public LiveData<PagedList<Content>> selectNoContent() {
        return new LivePagedListBuilder<>(new ObjectBoxDataSource.Factory<>(db.selectNoContentQ()), 1).build();
    }


    private LiveData<PagedList<Content>> getPagedContent(
            boolean isUniversal,
            ContentSearchManager.ContentSearchBundle searchBundle,
            List<Attribute> metadata) {
        boolean isCustomOrder = (searchBundle.getSortField() == Preferences.Constant.ORDER_FIELD_CUSTOM);

        ImmutablePair<Long, DataSource.Factory<Integer, Content>> contentRetrieval;
        if (isCustomOrder)
            contentRetrieval = getPagedContentByList(isUniversal, searchBundle, metadata);
        else
            contentRetrieval = getPagedContentByQuery(isUniversal, searchBundle, metadata);

        int nbPages = Preferences.getContentPageQuantity();
        int initialLoad = nbPages * 2;
        if (searchBundle.getLoadAll()) {
            // Trump Android's algorithm by setting a number of pages higher that the actual number of results
            // to avoid having a truncated result set (see issue #501)
            initialLoad = (int) Math.ceil(contentRetrieval.left * 1.0 / nbPages) * nbPages;
        }

        PagedList.Config cfg = new PagedList.Config.Builder().setEnablePlaceholders(!searchBundle.getLoadAll()).setInitialLoadSizeHint(initialLoad).setPageSize(nbPages).build();
        return new LivePagedListBuilder<>(contentRetrieval.right, cfg).build();
    }

    private ImmutablePair<Long, DataSource.Factory<Integer, Content>> getPagedContentByQuery(
            boolean isUniversal,
            ContentSearchManager.ContentSearchBundle searchBundle,
            List<Attribute> metadata) {
        boolean isRandom = (searchBundle.getSortField() == Preferences.Constant.ORDER_FIELD_RANDOM);

        Query<Content> query;
        if (isUniversal) {
            query = db.selectContentUniversalQ(searchBundle);
        } else {
            query = db.selectContentSearchContentQ(searchBundle, metadata);
        }

        if (isRandom) {
            List<Long> shuffledIds = db.getShuffledIds();
            return new ImmutablePair<>(query.count(), new ObjectBoxRandomDataSource.RandomDataSourceFactory<>(query, shuffledIds));
        } else return new ImmutablePair<>(query.count(), new ObjectBoxDataSource.Factory<>(query));
    }

    private ImmutablePair<Long, DataSource.Factory<Integer, Content>> getPagedContentByList(
            boolean isUniversal,
            ContentSearchManager.ContentSearchBundle searchBundle,
            List<Attribute> metadata) {
        long[] ids;

        if (isUniversal) {
            ids = db.selectContentUniversalByGroupItem(searchBundle);
        } else {
            ids = db.selectContentSearchContentByGroupItem(searchBundle, metadata);
        }

        return new ImmutablePair<>((long) ids.length, new ObjectBoxPredeterminedDataSource.PredeterminedDataSourceFactory<>(db::selectContentById, ids));
    }

    @Nullable
    public Content selectContent(long id) {
        return db.selectContentById(id);
    }

    public List<Content> selectContent(long[] id) {
        return db.selectContentById(Helper.getListFromPrimitiveArray(id));
    }

    @Nullable
    public Content selectContentBySourceAndUrl(@NonNull Site site, @NonNull String contentUrl, @Nullable String coverUrl) {
        final String coverUrlStart = (coverUrl != null) ? Content.getNeutralCoverUrlRoot(coverUrl, site) : "";
        return db.selectContentBySourceAndUrl(site, contentUrl, coverUrlStart);
    }

    public Set<String> selectAllSourceUrls(@NonNull Site site) {
        return db.selectAllContentUrls(site.getCode());
    }

    public Set<String> selectAllMergedUrls(@NonNull Site site) {
        return db.selectAllMergedContentUrls(site);
    }

    @Override
    public List<Content> searchTitlesWith(@NonNull String word, int[] contentStatusCodes) {
        return db.selectContentWithTitle(word, contentStatusCodes);
    }

    @Nullable
    public Content selectContentByStorageUri(@NonNull final String storageUri, boolean onlyFlagged) {
        // Select only the "document" part of the URI, as the "tree" part can vary
        String docPart = storageUri.substring(storageUri.indexOf("/document/"));
        return db.selectContentEndWithStorageUri(docPart, onlyFlagged);
    }

    public long insertContent(@NonNull final Content content) {
        return db.insertContent(content);
    }

    public long insertContentCore(@NonNull final Content content) {
        return db.insertContentCore(content);
    }

    public void updateContentStatus(@NonNull final StatusContent updateFrom, @NonNull final StatusContent updateTo) {
        db.updateContentStatus(updateFrom, updateTo);
    }

    public void updateContentDeleteFlag(long contentId, boolean flag) {
        db.updateContentDeleteFlag(contentId, flag);
    }

    public void deleteContent(@NonNull final Content content) {
        db.deleteContentById(content.getId());
    }

    public List<ErrorRecord> selectErrorRecordByContentId(long contentId) {
        return db.selectErrorRecordByContentId(contentId);
    }

    public void insertErrorRecord(@NonNull final ErrorRecord record) {
        db.insertErrorRecord(record);
    }

    public void deleteErrorRecords(long contentId) {
        db.deleteErrorRecords(contentId);
    }

    public void insertChapters(@NonNull final List<Chapter> chapters) {
        db.insertChapters(chapters);
    }

    public void deleteChapters(@NonNull final Content content) {
        db.deleteChaptersByContentId(content.getId());
    }

    @Override
    public void deleteChapter(@NonNull Chapter chapter) {
        db.deleteChapter(chapter.getId());
    }

    @Override
    public void clearDownloadParams(long contentId) {
        Content c = db.selectContentById(contentId);
        if (null == c) return;

        c.setDownloadParams("");
        db.insertContent(c);

        List<ImageFile> imgs = c.getImageFiles();
        if (null == imgs) return;
        for (ImageFile img : imgs) img.setDownloadParams("");
        db.insertImageFiles(imgs);
    }

    @Override
    public void shuffleContent() {
        db.shuffleContentIds();
    }

    @Override
    public long countAllExternalBooks() {
        return db.selectAllExternalBooksQ().count();
    }

    public long countAllInternalBooks(boolean favsOnly) {
        return db.selectAllInternalBooksQ(favsOnly, true).count();
    }

    public long countAllQueueBooks() {
        return db.selectAllQueueBooksQ().count();
    }

    public LiveData<Integer> countAllQueueBooksLive() {
        // This is not optimal because it fetches all the content and returns its size only
        // That's because ObjectBox v2.4.0 does not allow watching Query.count or Query.findLazy using LiveData, but only Query.find
        // See https://github.com/objectbox/objectbox-java/issues/776
        ObjectBoxLiveData<Content> livedata = new ObjectBoxLiveData<>(db.selectAllQueueBooksQ());

        MediatorLiveData<Integer> result = new MediatorLiveData<>();
        result.addSource(livedata, v -> result.setValue(v.size()));
        return result;
    }

    public void streamAllInternalBooks(boolean favsOnly, Consumer<Content> consumer) {
        Query<Content> query = db.selectAllInternalBooksQ(favsOnly, true);
        query.forEach(consumer::accept);
    }

    @Override
    public void deleteAllExternalBooks() {
        db.deleteContentById(db.selectAllExternalBooksQ().findIds());
        db.cleanupOrphanAttributes();
    }

    @Override
    public List<Group> selectGroups(long[] groupIds) {
        return db.selectGroups(groupIds);
    }

    @Override
    public List<Group> selectGroups(int grouping) {
        return db.selectGroupsQ(grouping, null, 0, false, -1, false, -1).find();
    }

    @Override
    public List<Group> selectGroups(int grouping, int subType) {
        return db.selectGroupsQ(grouping, null, 0, false, subType, false, -1).find();
    }

    @Override
    public LiveData<List<Group>> selectGroupsLive(
            int grouping,
            @Nullable String query,
            int orderField,
            boolean orderDesc,
            int artistGroupVisibility,
            boolean groupFavouritesOnly,
            int filterRating) {
        // Artist / group visibility filter is only relevant when the selected grouping is "By Artist"
        int subType = (grouping == Grouping.ARTIST.getId()) ? artistGroupVisibility : -1;

        LiveData<List<Group>> livedata = new ObjectBoxLiveData<>(db.selectGroupsQ(grouping, query, orderField, orderDesc, subType, groupFavouritesOnly, filterRating));
        LiveData<List<Group>> workingData = livedata;

        // Download date grouping : groups are empty as they are dynamically populated
        //   -> Manually add items inside each of them
        //   -> Manually set a cover for each of them
        if (grouping == Grouping.DL_DATE.getId()) {
            MediatorLiveData<List<Group>> livedata2 = new MediatorLiveData<>();
            livedata2.addSource(livedata, groups -> {
                List<Group> enrichedWithItems = Stream.of(groups).map(g -> enrichGroupWithItemsByDlDate(g, g.propertyMin, g.propertyMax)).toList();
                livedata2.setValue(enrichedWithItems);
            });
            workingData = livedata2;
        }

        // Custom grouping : "Ungrouped" special group is dynamically populated
        // -> Manually add items
        if (grouping == Grouping.CUSTOM.getId()) {
            MediatorLiveData<List<Group>> livedata2 = new MediatorLiveData<>();
            livedata2.addSource(livedata, groups -> {
                List<Group> enrichedWithItems = Stream.of(groups).map(this::enrichUngroupedWithItems).toList();
                livedata2.setValue(enrichedWithItems);
            });
            workingData = livedata2;
        }

        // Order by number of children (ObjectBox can't do that natively)
        if (Preferences.Constant.ORDER_FIELD_CHILDREN == orderField) {
            MediatorLiveData<List<Group>> result = new MediatorLiveData<>();
            result.addSource(workingData, groups -> {
                int sortOrder = orderDesc ? -1 : 1;
                List<Group> orderedByNbChildren = Stream.of(groups).sortBy(g -> g.getItems().size() * sortOrder).toList();
                result.setValue(orderedByNbChildren);
            });
            return result;
        }

        // Order by latest download date of children (ObjectBox can't do that natively)
        if (Preferences.Constant.ORDER_FIELD_DOWNLOAD_PROCESSING_DATE == orderField) {
            MediatorLiveData<List<Group>> result = new MediatorLiveData<>();
            result.addSource(workingData, groups -> {
                int sortOrder = orderDesc ? -1 : 1;
                List<Group> orderedByDlDate = Stream.of(groups).sortBy(g -> getLatestDlDate(g) * sortOrder).toList();
                result.setValue(orderedByDlDate);
            });
            return result;
        }

        return workingData;
    }

    private Group enrichGroupWithItemsByDlDate(@NonNull final Group g, int minDays, int maxDays) {
        List<GroupItem> items = selectGroupItemsByDlDate(g, minDays, maxDays);
        g.setItems(items);
        if (!items.isEmpty()) g.coverContent.setTarget(items.get(0).content.getTarget());

        return g;
    }

    private Group enrichUngroupedWithItems(@NonNull final Group g) {
        if (g.grouping.equals(Grouping.CUSTOM) && 1 == g.subtype) {
            List<GroupItem> items = Stream.of(db.selectUngroupedContentIds()).map(id -> new GroupItem(id, g, -1)).toList();
            g.setItems(items);
//            if (!items.isEmpty()) g.picture.setTarget(items.get(0).content.getTarget().getCover()); Can't query Content here as it is detached
        }
        return g;
    }

    private long getLatestDlDate(@NonNull final Group g) {
        // Manually select all content as g.getContents won't work (unresolved items)
        List<Content> contents = db.selectContentById(g.getContentIds());
        if (contents != null) {
            Optional<Long> maxDlDate = Stream.of(contents).map(Content::getDownloadDate).max(Long::compareTo);
            return maxDlDate.isPresent() ? maxDlDate.get() : 0;
        }
        return 0;
    }

    @Nullable
    public Group selectGroup(long groupId) {
        return db.selectGroup(groupId);
    }

    @Nullable
    public Group selectGroupByName(int grouping, @NonNull final String name) {
        return db.selectGroupByName(grouping, name);
    }

    // Does NOT check name unicity
    public long insertGroup(Group group) {
        // Auto-number max order when not provided
        if (-1 == group.order)
            group.order = db.getMaxGroupOrderFor(group.grouping) + 1;
        return db.insertGroup(group);
    }

    public long countGroupsFor(Grouping grouping) {
        return db.countGroupsFor(grouping);
    }

    public void deleteGroup(long groupId) {
        db.deleteGroup(groupId);
    }

    public void deleteAllGroups(Grouping grouping) {
        db.deleteGroupItemsByGrouping(grouping.getId());
        db.selectGroupsByGroupingQ(grouping.getId()).remove();
    }

    public void flagAllGroups(Grouping grouping) {
        db.flagGroupsForDeletion(db.selectGroupsByGroupingQ(grouping.getId()).find());
    }

    public void deleteAllFlaggedGroups() {
        Query<Group> flaggedGroups = db.selectFlaggedGroupsQ();

        // Delete related GroupItems first
        List<Group> groups = flaggedGroups.find();
        for (Group g : groups) db.deleteGroupItemsByGroup(g.id);

        // Actually delete the Group
        flaggedGroups.remove();
    }

    public long insertGroupItem(GroupItem item) {
        // Auto-number max order when not provided
        if (-1 == item.order)
            item.order = db.getMaxGroupItemOrderFor(item.getGroupId()) + 1;

        // If target group doesn't have a cover, get the corresponding Content's
        ToOne<Content> groupCoverContent = item.group.getTarget().coverContent;
        if (!groupCoverContent.isResolvedAndNotNull())
            groupCoverContent.setAndPutTarget(item.content.getTarget());

        return db.insertGroupItem(item);
    }

    public List<GroupItem> selectGroupItems(long contentId, Grouping grouping) {
        return db.selectGroupItems(contentId, grouping.getId());
    }

    private List<GroupItem> selectGroupItemsByDlDate(@NonNull final Group group, int minDays, int maxDays) {
        List<Content> contentResult = db.selectContentByDlDate(minDays, maxDays);
        return Stream.of(contentResult).map(c -> new GroupItem(c, group, -1)).toList();
    }

    public void deleteGroupItems(@NonNull final List<Long> groupItemIds) {
        // Check if one of the GroupItems to delete is linked to the content that contains the group's cover picture
        List<GroupItem> groupItems = db.selectGroupItems(Helper.getPrimitiveArrayFromList(groupItemIds));
        for (GroupItem gi : groupItems) {
            ToOne<Content> groupCoverContent = gi.group.getTarget().coverContent;
            // If so, remove the cover picture
            if (groupCoverContent.isResolvedAndNotNull() && groupCoverContent.getTargetId() == gi.content.getTargetId())
                gi.group.getTarget().coverContent.setAndPutTarget(null);
        }

        db.deleteGroupItems(Helper.getPrimitiveArrayFromList(groupItemIds));
    }


    public List<Content> selectAllQueueBooks() {
        return db.selectAllQueueBooksQ().find();
    }

    public void flagAllInternalBooks(boolean includePlaceholders) {
        db.flagContentsForDeletion(db.selectAllInternalBooksQ(false, includePlaceholders).find(), true);
    }

    public void deleteAllInternalBooks(boolean resetRemainingImagesStatus) {
        db.deleteContentById(db.selectAllInternalBooksQ(false, true).findIds());

        // Switch status of all remaining images (i.e. from queued books) to SAVED, as we cannot guarantee the files are still there
        if (resetRemainingImagesStatus) {
            long[] remainingContentIds = db.selectAllQueueBooksQ().findIds();
            for (long contentId : remainingContentIds)
                db.updateImageContentStatus(contentId, null, StatusContent.SAVED);
        }
    }

    public void deleteAllFlaggedBooks(boolean resetRemainingImagesStatus) {
        db.deleteContentById(db.selectAllFlaggedBooksQ().findIds());

        // Switch status of all remaining images (i.e. from queued books) to SAVED, as we cannot guarantee the files are still there
        if (resetRemainingImagesStatus) {
            long[] remainingContentIds = db.selectAllQueueBooksQ().findIds();
            for (long contentId : remainingContentIds)
                db.updateImageContentStatus(contentId, null, StatusContent.SAVED);
        }
    }

    public void flagAllErrorBooksWithJson() {
        db.flagContentsForDeletion(db.selectAllErrorJsonBooksQ().find(), true);
    }

    public void deleteAllQueuedBooks() {
        Timber.i("Cleaning up queue");
        db.deleteContentById(db.selectAllQueueBooksQ().findIds());
        db.deleteQueueRecords();
    }

    public void insertImageFile(@NonNull ImageFile img) {
        db.insertImageFile(img);
    }

    @Override
    public void insertImageFiles(@NonNull List<ImageFile> imgs) {
        db.insertImageFiles(imgs);
    }

    public void replaceImageList(long contentId, @NonNull final List<ImageFile> newList) {
        db.replaceImageFiles(contentId, newList);
    }

    public void updateImageContentStatus(long contentId, StatusContent updateFrom, @NonNull StatusContent updateTo) {
        db.updateImageContentStatus(contentId, updateFrom, updateTo);
    }

    public void updateImageFileStatusParamsMimeTypeUriSize(@NonNull ImageFile image) {
        db.updateImageFileStatusParamsMimeTypeUriSize(image);
    }

    public void deleteImageFiles(@NonNull List<ImageFile> imgs) {
        // Delete the page
        db.deleteImageFiles(imgs);

        // Lists all relevant content
        List<Long> contents = Stream.of(imgs).filter(i -> i.getContent() != null).map(i -> i.getContent().getTargetId()).distinct().toList();

        // Update the content with its new size
        for (Long contentId : contents) {
            Content content = db.selectContentById(contentId);
            if (content != null) {
                content.computeSize();
                db.insertContent(content);
            }
        }
    }

    @Nullable
    public ImageFile selectImageFile(long id) {
        return db.selectImageFile(id);
    }

    public List<ImageFile> selectImageFiles(long[] ids) {
        return db.selectImageFiles(ids);
    }

    public LiveData<List<ImageFile>> selectDownloadedImagesFromContentLive(long id) {
        return new ObjectBoxLiveData<>(db.selectDownloadedImagesFromContentQ(id));
    }

    @Override
    public List<ImageFile> selectDownloadedImagesFromContent(long id) {
        return db.selectDownloadedImagesFromContentQ(id).find();
    }

    public Map<StatusContent, ImmutablePair<Integer, Long>> countProcessedImagesById(long contentId) {
        return db.countProcessedImagesById(contentId);
    }

    public Map<Site, ImmutablePair<Integer, Long>> selectPrimaryMemoryUsagePerSource() {
        return db.selectPrimaryMemoryUsagePerSource();
    }

    public Map<Site, ImmutablePair<Integer, Long>> selectExternalMemoryUsagePerSource() {
        return db.selectExternalMemoryUsagePerSource();
    }

    public void addContentToQueue(@NonNull final Content content, StatusContent targetImageStatus, int position, long replacedContentId, boolean isQueueActive) {
        if (targetImageStatus != null)
            db.updateImageContentStatus(content.getId(), null, targetImageStatus);

        content.setStatus(StatusContent.PAUSED);
        content.setIsBeingDeleted(false); // Remove any UI animation
        if (replacedContentId > -1) content.setContentIdToReplace(replacedContentId);
        db.insertContent(content);

        if (!db.isContentInQueue(content)) {
            int targetPosition;
            if (position == Preferences.Constant.QUEUE_NEW_DOWNLOADS_POSITION_BOTTOM) {
                targetPosition = (int) db.selectMaxQueueOrder() + 1;
            } else { // Top - don't put #1 if queue is active not to interrupt current download
                targetPosition = (isQueueActive) ? 2 : 1;
            }
            insertQueueAndRenumber(content.getId(), targetPosition);
        }
    }

    private void insertQueueAndRenumber(long contentId, int order) {
        List<QueueRecord> queue = db.selectQueueRecordsQ(null).find();
        QueueRecord newRecord = new QueueRecord(contentId, order);

        // Put in the right place
        if (order > queue.size()) queue.add(newRecord);
        else {
            int newOrder = Math.min(queue.size() + 1, order);
            queue.add(newOrder - 1, newRecord);
        }
        // Renumber everything and save
        int index = 1;
        for (QueueRecord qr : queue) qr.setRank(index++);
        db.updateQueue(queue);
    }

    private List<Long> contentIdSearch(
            boolean isUniversal,
            ContentSearchManager.ContentSearchBundle searchBundle,
            List<Attribute> metadata) {
        if (isUniversal) {
            return Helper.getListFromPrimitiveArray(db.selectContentUniversalId(searchBundle, ContentHelper.getLibraryStatuses()));
        } else {
            return Helper.getListFromPrimitiveArray(db.selectContentSearchId(searchBundle, metadata));
        }
    }

    private SearchHelper.AttributeQueryResult pagedAttributeSearch(
            @NonNull List<AttributeType> attrTypes,
            String filter,
            long groupId,
            List<Attribute> attrs,
            @ContentHelper.Location int location,
            @ContentHelper.Type int contentType,
            boolean includeFreeAttrs,
            int sortOrder,
            int pageNum,
            int itemPerPage) {
        List<Attribute> attributes = new ArrayList<>();
        long totalSelectedAttributes = 0;

        if (!attrTypes.isEmpty()) {
            if (attrTypes.get(0).equals(AttributeType.SOURCE)) {
                attributes.addAll(db.selectAvailableSources(groupId, attrs, location, contentType, includeFreeAttrs));
                totalSelectedAttributes = attributes.size();
            } else {
                for (AttributeType type : attrTypes) {
                    // TODO fix sorting when concatenating both lists
                    attributes.addAll(db.selectAvailableAttributes(type, groupId, attrs, location, contentType, includeFreeAttrs, filter, sortOrder, pageNum, itemPerPage));
                    totalSelectedAttributes += db.countAvailableAttributes(type, groupId, attrs, location, contentType, includeFreeAttrs, filter);
                }
            }
        }

        return new SearchHelper.AttributeQueryResult(attributes, totalSelectedAttributes);
    }

    private SparseIntArray countAttributes(long groupId,
                                           List<Attribute> filter,
                                           @ContentHelper.Location int location,
                                           @ContentHelper.Type int contentType) {
        SparseIntArray result;
        if ((null == filter || filter.isEmpty()) && 0 == location && 0 == contentType && -1 == groupId) {
            result = db.countAvailableAttributesPerType();
            result.put(AttributeType.SOURCE.getCode(), db.selectAvailableSources().size());
        } else {
            result = db.countAvailableAttributesPerType(groupId, filter, location, contentType);
            result.put(AttributeType.SOURCE.getCode(), db.selectAvailableSources(groupId, filter, location, contentType, false).size());
        }

        return result;
    }

    public LiveData<List<QueueRecord>> selectQueueLive() {
        return new ObjectBoxLiveData<>(db.selectQueueRecordsQ(null));
    }

    @Override
    public LiveData<List<QueueRecord>> selectQueueLive(String query) {
        return new ObjectBoxLiveData<>(db.selectQueueRecordsQ(query));
    }

    @Override
    public List<QueueRecord> selectQueue() {
        return db.selectQueueRecordsQ(null).find();
    }

    public void updateQueue(@NonNull List<QueueRecord> queue) {
        db.updateQueue(queue);
    }

    public void deleteQueue(@NonNull Content content) {
        db.deleteQueueRecords(content);
    }

    public void deleteQueue(int index) {
        db.deleteQueueRecords(index);
    }

    public void deleteQueueRecordsCore() {
        db.deleteQueueRecords();
    }

    public SiteHistory selectHistory(@NonNull Site s) {
        return db.selectHistory(s);
    }

    public void insertSiteHistory(@NonNull Site site, @NonNull String url) {
        db.insertSiteHistory(site, url);
    }

    // BOOKMARKS

    public long countAllBookmarks() {
        return db.selectBookmarksQ(null).count();
    }

    public List<SiteBookmark> selectAllBookmarks() {
        return db.selectBookmarksQ(null).find();
    }

    public void deleteAllBookmarks() {
        db.selectBookmarksQ(null).remove();
    }

    public List<SiteBookmark> selectBookmarks(@NonNull Site s) {
        return db.selectBookmarksQ(s).find();
    }

    @Override
    public SiteBookmark selectHomepage(@NonNull Site s) {
        return db.selectHomepage(s);
    }

    public long insertBookmark(@NonNull final SiteBookmark bookmark) {
        // Auto-number max order when not provided
        if (-1 == bookmark.getOrder())
            bookmark.setOrder(db.getMaxBookmarkOrderFor(bookmark.getSite()) + 1);
        return db.insertBookmark(bookmark);
    }

    public void insertBookmarks(@NonNull List<SiteBookmark> bookmarks) {
        // Mass insert method; no need to renumber here
        db.insertBookmarks(bookmarks);
    }

    public void deleteBookmark(long bookmarkId) {
        db.deleteBookmark(bookmarkId);
    }


    // SEARCH HISTORY

    public LiveData<List<SearchRecord>> selectSearchRecordsLive() {
        return new ObjectBoxLiveData<>(db.selectSearchRecordsQ());
    }

    private List<SearchRecord> selectSearchRecords() {
        return db.selectSearchRecordsQ().find();
    }

    public void insertSearchRecord(@NonNull SearchRecord record, int limit) {
        List<SearchRecord> records = selectSearchRecords();
        if (records.contains(record)) return;

        while (records.size() >= limit) {
            db.deleteSearchRecord(records.get(0).id);
            records.remove(0);
        }
        records.add(record);
        db.insertSearchRecords(records);
    }

    public void deleteAllSearchRecords() {
        db.selectSearchRecordsQ().remove();
    }


    // RENAMING RULES

    @Nullable
    public RenamingRule selectRenamingRule(long id) {
        return db.selectRenamingRule(id);
    }

    public LiveData<List<RenamingRule>> selectRenamingRulesLive(@NonNull AttributeType type, String nameFilter) {
        return new ObjectBoxLiveData<>(db.selectRenamingRulesQ(type, StringHelper.protect(nameFilter)));
    }

    public List<RenamingRule> selectRenamingRules(@NonNull AttributeType type, String nameFilter) {
        Query<RenamingRule> query = db.selectRenamingRulesQ(type, StringHelper.protect(nameFilter));
        return query.find();
    }

    public long insertRenamingRule(@NonNull RenamingRule rule) {
        return db.insertRenamingRule(rule);
    }

    public void insertRenamingRules(@NonNull List<RenamingRule> rules) {
        db.insertRenamingRules(rules);
    }

    public void deleteRenamingRules(List<Long> ids) {
        db.deleteRenamingRules(Helper.getPrimitiveArrayFromList(ids));
    }

    public void deleteAllRenamingRules() {
        db.deleteAllRenamingRules();
    }


    // ONE-TIME USE QUERIES (MIGRATION & CLEANUP)

    // API29 migration query
    @Override
    public Single<List<Long>> selectOldStoredBookIds() {
        return Single.fromCallable(() -> Helper.getListFromPrimitiveArray(db.selectOldStoredContentQ().findIds()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    // API29 migration query
    @Override
    public long countOldStoredContent() {
        return db.selectOldStoredContentQ().count();
    }
}
