package com.morpheusdata.hyperv.datasets

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.*
import com.morpheusdata.core.providers.AbstractDatasetProvider
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

/**
 * @author rahul.ray
 */

@Slf4j
class HyperVVMDatasetProvider extends AbstractDatasetProvider<VirtualImage, Long> {

    public static final providerName = 'Hyper-V Virtual Images'
    public static final providerNamespace = 'hyperv'
    public static final providerKey = 'hypervVirtualImages'
    public static final providerDescription = 'Get virtual images for Hyper-V provisioning.'


    HyperVVMDatasetProvider(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheusContext = morpheus
    }

    @Override
    DatasetInfo getInfo() {
        new DatasetInfo(
                name: providerName,
                namespace: providerNamespace,
                key: providerKey,
                description: providerDescription
        )
    }

    /**
     * the class type of the data for this provider
     * @return the class this provider operates on
     */
    @Override
    Class<VirtualImage> getItemType() {
        VirtualImage.class
    }

    /**
     * list the values in the dataset
     * @param query the user and map of query params or options to apply to the list
     * @return a list of objects
     */
    @Override
    Observable<VirtualImage> list(DatasetQuery datasetQuery) {
        DataQuery query = buildQuery(datasetQuery)
        return morpheus.async.virtualImage.list(query)
    }

    /**
     * list the values in the dataset in a common format of a name value pair. (example: [[name: "blue", value: 1]])
     * @param query a DatasetQuery containing the user and map of query params or options to apply to the list
     * @return a list of maps that have name value pairs of the items
     */
    @Override
    Observable<Map> listOptions(DatasetQuery datasetQuery) {
        DataQuery query = buildQuery(datasetQuery)
        morpheus.async.virtualImage.listIdentityProjections(query).map { VirtualImageIdentityProjection item ->
            return [name: item.name, value: item.id]
        }
    }

    /**
     * returns the matching item from the list with the value as a string or object - since option values
     *   are often stored or passed as strings or unknown types. lets the provider do its own conversions to call
     *   item with the proper type. did object for flexibility but probably is usually a string
     * @param value the value to match the item in the list
     * @return the item
     */
    @Override
    VirtualImage fetchItem(Object value) {
        def rtn = null
        if (value instanceof Long) {
            rtn = item((Long) value)
        } else if (value instanceof CharSequence) {
            def longValue = MorpheusUtils.parseLongConfig(value)
            if (longValue) {
                rtn = item(longValue)
            }
        }
        return rtn
    }

    /**
     * returns the item from the list with the matching value
     * @param value the value to match the item in the list
     * @return the
     */
    @Override
    VirtualImage item(Long value) {
        return morpheus.services.virtualImage.get(value)
    }

    /**
     * gets the name for an item
     * @param item an item
     * @return the corresponding name for the name/value pair list
     */
    @Override
    String itemName(VirtualImage item) {
        return item.name
    }

    /**
     * gets the value for an item
     * @param item an item
     * @return the corresponding value for the name/value pair list
     */
    @Override
    Long itemValue(VirtualImage item) {
        return item.id
    }

    DataQuery buildQuery(DatasetQuery datasetQuery) {
        log.debug("datasetQuery.parameters: ${datasetQuery.parameters}")
        Long cloudId = datasetQuery.get("zoneId")?.toLong()
        Cloud tmpZone = cloudId ? morpheus.services.cloud.get(cloudId) : null
        DataQuery query = new DatasetQuery().withFilters(
                new DataOrFilter(
                        new DataFilter("visibility", "public"),
                        new DataFilter("accounts.id", datasetQuery.get("accountId")?.toLong()),
                        new DataFilter("owner.id", datasetQuery.get("accountId")?.toLong())
                ),
                new DataFilter("deleted", false),
                new DataOrFilter(
                        new DataFilter("imageType", 'vhd'),
                        new DataFilter("imageType", 'vhdx')
                ),
                new DataOrFilter(
                        new DataFilter("status", 'notIn', ['Saving', 'Failed', 'Converting', 'Queued']),
                        new DataFilter("status", null)
                ),
                new DataOrFilter(
                        new DataFilter('userUploaded', true),
                        new DataFilter("locations.refType", 'ComputeZone'),
                )
        )
        return query.withSort("name", DataQuery.SortOrder.asc)
    }
}
