package com.genersoft.iot.vmp.gb28181.service.impl;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.event.EventPublisher;
import com.genersoft.iot.vmp.gb28181.event.subscribe.catalog.CatalogEvent;
import com.genersoft.iot.vmp.gb28181.service.IPlatformChannelService;
import com.genersoft.iot.vmp.gb28181.dao.DeviceChannelMapper;
import com.genersoft.iot.vmp.gb28181.dao.PlatformMapper;
import com.genersoft.iot.vmp.gb28181.dao.PlatformChannelMapper;
import com.genersoft.iot.vmp.gb28181.controller.bean.ChannelReduce;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.util.ObjectUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author lin
 */
@Slf4j
@Service
@DS("master")
public class PlatformChannelServiceImpl implements IPlatformChannelService {

    @Autowired
    private PlatformChannelMapper platformChannelMapper;

    @Autowired
    TransactionDefinition transactionDefinition;

    @Autowired
    DataSourceTransactionManager dataSourceTransactionManager;

    @Autowired
    private SubscribeHolder subscribeHolder;


    @Autowired
    private DeviceChannelMapper deviceChannelMapper;

    @Autowired
    private PlatformMapper platformMapper;

    @Autowired
    EventPublisher eventPublisher;

    @Override
    public int updateChannelForGB(String platformId, List<ChannelReduce> channelReduces, String catalogId) {
        Platform platform = platformMapper.getParentPlatByServerGBId(platformId);
        if (platform == null) {
            log.warn("更新级联通道信息时未找到平台{}的信息", platformId);
            return 0;
        }
        Map<Integer, ChannelReduce> deviceAndChannels = new HashMap<>();
        for (ChannelReduce channelReduce : channelReduces) {
            channelReduce.setCatalogId(catalogId);
            deviceAndChannels.put(channelReduce.getId(), channelReduce);
        }
        List<Integer> deviceAndChannelList = new ArrayList<>(deviceAndChannels.keySet());
        // 查询当前已经存在的
        List<Integer> channelIds = platformChannelMapper.findChannelRelatedPlatform(platformId, channelReduces);
        if (deviceAndChannelList != null) {
            deviceAndChannelList.removeAll(channelIds);
        }
        for (Integer channelId : channelIds) {
            deviceAndChannels.remove(channelId);
        }
        List<ChannelReduce> channelReducesToAdd = new ArrayList<>(deviceAndChannels.values());
        // 对剩下的数据进行存储
        int allCount = 0;
        boolean result = false;
        TransactionStatus transactionStatus = dataSourceTransactionManager.getTransaction(transactionDefinition);
        int limitCount = 50;
        if (channelReducesToAdd.size() > 0) {
            if (channelReducesToAdd.size() > limitCount) {
                for (int i = 0; i < channelReducesToAdd.size(); i += limitCount) {
                    int toIndex = i + limitCount;
                    if (i + limitCount > channelReducesToAdd.size()) {
                        toIndex = channelReducesToAdd.size();
                    }
                    int count = platformChannelMapper.addChannels(platformId, channelReducesToAdd.subList(i, toIndex));
                    result = result || count < 0;
                    allCount += count;
                    log.info("[关联通道]国标通道 平台：{}, 共需关联通道数:{}, 已关联：{}", platformId, channelReducesToAdd.size(), toIndex);
                }
            }else {
                allCount = platformChannelMapper.addChannels(platformId, channelReducesToAdd);
                result = result || allCount < 0;
                log.info("[关联通道]国标通道 平台：{}, 关联通道数:{}", platformId, channelReducesToAdd.size());
            }

            if (result) {
                //事务回滚
                dataSourceTransactionManager.rollback(transactionStatus);
                allCount = 0;
            }else {
                log.info("[关联通道]国标通道 平台：{}, 正在存入数据库", platformId);
                dataSourceTransactionManager.commit(transactionStatus);

            }
            SubscribeInfo catalogSubscribe = subscribeHolder.getCatalogSubscribe(platformId);
            if (catalogSubscribe != null) {
                List<CommonGBChannel> deviceChannelList = getDeviceChannelListByChannelReduceList(channelReducesToAdd, catalogId, platform);
                if (deviceChannelList != null) {
                    eventPublisher.catalogEventPublish(platform.getId(), deviceChannelList, CatalogEvent.ADD);
                }
            }
            log.info("[关联通道]国标通道 平台：{}, 存入数据库成功", platformId);
        }
        return allCount;
    }

    private List<CommonGBChannel> getDeviceChannelListByChannelReduceList(List<ChannelReduce> channelReduces, String catalogId, Platform platform) {
        List<CommonGBChannel> deviceChannelList = new ArrayList<>();
//        if (!channelReduces.isEmpty()){
//            PlatformCatalog catalog = catalogManager.selectByPlatFormAndCatalogId(platform.getServerGBId(),catalogId);
//            if (catalog == null && catalogId.equals(platform.getDeviceGBId())) {
//                for (ChannelReduce channelReduce : channelReduces) {
//                    DeviceChannel deviceChannel = deviceChannelMapper.getOne(channelReduce.getId());
//                    deviceChannel.setParental(0);
//                    deviceChannel.setCivilCode(platform.getServerGBDomain());
//                    deviceChannelList.add(deviceChannel);
//                }
//                return deviceChannelList;
//            } else if (catalog == null && !catalogId.equals(platform.getDeviceGBId())) {
//                log.warn("未查询到目录{}的信息", catalogId);
//                return null;
//            }
//            for (ChannelReduce channelReduce : channelReduces) {
//                DeviceChannel deviceChannel = deviceChannelMapper.getOne(channelReduce.getId());
//                deviceChannel.setParental(0);
//                deviceChannel.setCivilCode(catalog.getCivilCode());
//                deviceChannel.setParentId(catalog.getParentId());
//                deviceChannel.setBusinessGroupId(catalog.getBusinessGroupId());
//                deviceChannelList.add(deviceChannel);
//            }
//        }
        return deviceChannelList;
    }

    @Override
    public int delAllChannelForGB(String platformId, String catalogId) {

        int result;
        if (platformId == null) {
            return 0;
        }
        Platform platform = platformMapper.getParentPlatByServerGBId(platformId);
        if (platform == null) {
            return 0;
        }
        if (ObjectUtils.isEmpty(catalogId)) {
           catalogId = null;
        }

        List<CommonGBChannel> deviceChannels = platformChannelMapper.queryAllChannelInCatalog(platformId, catalogId);
        eventPublisher.catalogEventPublish(platform.getId(), deviceChannels, CatalogEvent.DEL);

        return platformChannelMapper.delChannelForGBByCatalogId(platformId, catalogId);
    }
}
