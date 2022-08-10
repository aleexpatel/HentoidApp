package me.devsaki.hentoid.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.annimon.stream.Stream
import io.reactivex.Completable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposables
import io.reactivex.schedulers.Schedulers
import me.devsaki.hentoid.database.CollectionDAO
import me.devsaki.hentoid.database.domains.Attribute
import me.devsaki.hentoid.database.domains.Content
import me.devsaki.hentoid.enums.AttributeType
import me.devsaki.hentoid.util.ContentHelper
import me.devsaki.hentoid.util.GroupHelper
import me.devsaki.hentoid.util.Preferences
import me.devsaki.hentoid.util.SearchHelper.AttributeQueryResult
import org.threeten.bp.Instant
import timber.log.Timber


class MetadataEditViewModel(
    application: Application,
    private val dao: CollectionDAO
) : AndroidViewModel(application) {

    // Disposables (to cleanup Rx calls and avoid memory leaks)
    private val compositeDisposable = CompositeDisposable()
    private val countDisposable = Disposables.empty()
    private var filterDisposable = Disposables.empty()
    private var leaveDisposable = Disposables.empty()

    // LIVEDATAS
    private val contentList = MutableLiveData<List<Content>>()
    private val attributeTypes = MutableLiveData<List<AttributeType>>()
    private val contentAttributes = MutableLiveData<List<Attribute>>()
    private val libraryAttributes = MutableLiveData<AttributeQueryResult>()


    init {
        contentAttributes.value = ArrayList()
    }

    override fun onCleared() {
        super.onCleared()
        filterDisposable.dispose()
        countDisposable.dispose()
        dao.cleanup()
        compositeDisposable.clear()
    }


    fun getContent(): LiveData<List<Content>> {
        return contentList
    }

    fun getAttributeTypes(): LiveData<List<AttributeType>> {
        return attributeTypes
    }

    fun getContentAttributes(): LiveData<List<Attribute>> {
        return contentAttributes
    }

    fun getLibraryAttributes(): LiveData<AttributeQueryResult> {
        return libraryAttributes
    }


    /**
     * Load the given list of Content
     *
     * @param contentId  IDs of the Contents to load
     */
    fun loadContent(contentId: LongArray) {
        val contents = dao.selectContent(contentId.filter { id -> id > 0 }.toLongArray())
        val rawAttrs = java.util.ArrayList<Attribute>()
        contents.forEach { c ->
            rawAttrs.addAll(c.attributes)
        }
        val attrsCount = rawAttrs.groupingBy { a -> a }.eachCount()
        attrsCount.entries.forEach { it.key.count = it.value }

        contentList.postValue(contents)
        contentAttributes.postValue(attrsCount.keys.toList())
    }

    fun setCover(order: Int) {
        val content = contentList.value?.get(0)
        if (content != null) {
            content.imageFiles?.forEach {
                if (it.order == order) {
                    it.setIsCover(true)
                    content.coverImageUrl = it.url
                } else {
                    it.setIsCover(false)
                }
            }
            contentList.postValue(Stream.of(content).toList())
        }
    }

    /**
     * Set the attributes type to search in the Atttribute search
     *
     * @param value Attribute types the searches will be performed for
     */
    fun setAttributeTypes(value: List<AttributeType>) {
        attributeTypes.postValue(value)
    }

    /**
     * Set and run the query to perform the Attribute search
     *
     * @param query        Content of the attribute name to search (%s%)
     * @param pageNum      Number of the "paged" result to fetch
     * @param itemsPerPage Number of items per result "page"
     */
    fun setAttributeQuery(query: String, pageNum: Int, itemsPerPage: Int) {
        filterDisposable.dispose()
        filterDisposable = dao.selectAttributeMasterDataPaged(
            attributeTypes.value!!,
            query,
            -1,
            emptyList(),
            ContentHelper.Location.ANY,
            ContentHelper.Type.ANY,
            true,
            pageNum,
            itemsPerPage,
            Preferences.getSearchAttributesSortOrder()
        ).subscribe { value: AttributeQueryResult? ->
            libraryAttributes.postValue(value)
        }
    }

    /**
     * Add the given attribute to the selected books
     *
     * @param attr Attribute to add to current selection
     */
    fun addContentAttribute(attr: Attribute) {
        setAttr(attr, null)
    }

    /**
     * Remove the given attribute from the selected books
     *
     * @param attr Attribute to remove to current selection
     */
    fun removeContentAttribute(attr: Attribute) {
        setAttr(null, attr)
    }

    fun createAssignNewAttribute(attrName: String, type: AttributeType) {
        val attr = ContentHelper.addAttribute(type, attrName, dao)
        addContentAttribute(attr)
    }

    /**
     * Add and remove the given attributes from the selected books
     *
     * @param toAdd Attribute to add to current selection
     * @param toRemove Attribute to remove to current selection
     */
    private fun setAttr(toAdd: Attribute?, toRemove: Attribute?) {
        // Update displayed attributes
        val newAttrs = ArrayList<Attribute>()
        if (contentAttributes.value != null) newAttrs.addAll(contentAttributes.value!!) // Create new instance to make ListAdapter.submitList happy

        if (toAdd != null) {
            toAdd.count = contentList.value!!.size
            newAttrs.add(toAdd)
        }
        if (toRemove != null) newAttrs.remove(toRemove)

        contentAttributes.value = newAttrs

        // Update Contents
        val contents = ArrayList<Content>()
        if (contentList.value != null) {
            contents.addAll(contentList.value!!)
            contents.forEach {
                val attrs = it.attributes
                if (toAdd != null) attrs.add(toAdd)
                if (toRemove != null) attrs.remove(toRemove)
                it.putAttributes(attrs)
            }
            contentList.postValue(contents)
        }
    }

    fun setTitle(value: String) {
        // Update Contents
        val contents = ArrayList<Content>()
        if (contentList.value != null) {
            contents.addAll(contentList.value!!)
            contents.forEach { c -> c.title = value }
            contentList.postValue(contents)
        }
    }

    fun saveContent() {
        leaveDisposable = Completable.fromRunnable { doSaveContent() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { leaveDisposable.dispose() }
            ) { t: Throwable? -> Timber.e(t) }
    }

    private fun doSaveContent() {
        contentList.value?.forEach {
            it.lastEditDate = Instant.now().toEpochMilli()
            dao.insertContent(it)
            // TODO update artist groups
            ContentHelper.persistJson(getApplication(), it)
        }
        GroupHelper.updateGroupsJson(getApplication(), dao)
    }
}