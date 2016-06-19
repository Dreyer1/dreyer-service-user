package com.dreyer.facade.user.impl;

import com.dreyer.common.constants.RedisKeyConstants;
import com.dreyer.common.enums.MessageType;
import com.dreyer.common.enums.PublicEnum;
import com.dreyer.common.page.PageParam;
import com.dreyer.common.page.Pager;
import com.dreyer.common.params.MailParam;
import com.dreyer.common.util.*;
import com.dreyer.core.user.dao.UserMapper;
import com.dreyer.facade.notify.service.NotifySendFacade;
import com.dreyer.facade.user.criteria.UserCriteria;
import com.dreyer.facade.user.entity.User;
import com.dreyer.facade.user.exceptions.UserBizException;
import com.dreyer.facade.user.service.UserFacade;
import com.dreyer.facade.user.vo.UserVo;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @description 用户服务接口实现类
 * @author: Dreyer
 * @date: 16/6/16 下午10:37
 */
@Service("userFacade")
public class UserFacadeImpl implements UserFacade {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private NotifySendFacade notifySendFacade;

    /**
     * 单个线程的线程池
     */
    private static ExecutorService executorService = Executors.newSingleThreadExecutor();

    private static final Logger logger = Logger.getLogger(UserFacadeImpl.class);

    public boolean save(User user) {
        // 校验用户名是否已存在
        User u = findByUserName(user.getUserName());
        if (u != null) {
            throw UserBizException.USERNAME_IS_EXIST;
        }
        int flag = userMapper.insertSelective(user);
        // 写入Redis缓存,要保证上行执行插入操作后,能获取到user.getId()的值
        String userKey = String.format(RedisKeyConstants.KEY_USER, user.getId());
        RedisUtils.save(userKey, user);

        return flag > 0;
    }

    public boolean deleteById(long id) {
        User user = findById(id);
        if (user == null) {
            throw UserBizException.USER_IS_NOT_EXIST;
        }
        // 从Redis中删除
        String userKey = String.format(RedisKeyConstants.KEY_USER, id);
        RedisUtils.del(userKey);
        user.setIsDelete(PublicEnum.YES.name());
        return update(user);
    }

    public boolean update(User user) {
        int flag = userMapper.updateByPrimaryKeySelective(user);
        // 再次写入Redis,覆盖之前的值
        String userKey = String.format(RedisKeyConstants.KEY_USER, user.getId());
        RedisUtils.save(userKey, user);
        return flag > 0;
    }

    public User findById(long id) {
        // 先从Redis中取数据,取不到则从DB获取
        String userKey = String.format(RedisKeyConstants.KEY_USER, id);
        User user = (User) RedisUtils.get(userKey);
        if (user == null) {
            logger.info("get user from DB...");
            user = userMapper.selectByPrimaryKey(id);
            // 过滤已删除标识的
            if (user != null && user.getIsDelete() != null && user.getIsDelete().equals(PublicEnum.NO.name())) {
                RedisUtils.save(userKey, user);
            } else {
                return null;
            }
        }
        logger.info("get user from Redis...");
        return user;
    }

    public User findByUserName(String userName) {
        UserCriteria userCriteria = new UserCriteria();
        UserCriteria.Criteria criteria = userCriteria.createCriteria();
        criteria.andUserNameEqualTo(userName);
        criteria.andIsDeleteEqualTo(PublicEnum.NO.name());

        List<User> users = userMapper.selectByExample(userCriteria);

        return CollectionUtils.isEmpty(users) ? null : users.get(0);
    }

    public Pager<UserVo> list(Map<String, Object> param, PageParam pageParam) {
        PageParam.buildPageParam(param, pageParam);

        List<User> users = userMapper.list(param);
        List<UserVo> userVos = UserVo.build(users);
        int count = userMapper.count(param);

        return new Pager<UserVo>(pageParam.getCurrentPageIndex(), pageParam.getPageSize(), count, pageParam.getSort(), pageParam.getOrder(), userVos);
    }

    @Override
    public boolean login(String userName, String passWord, String loginIp) {
        if (StringUtil.isEmpty(userName) || StringUtil.isEmpty(passWord)) {
            return false;
        }
        UserCriteria userCriteria = new UserCriteria();
        UserCriteria.Criteria criteria = userCriteria.createCriteria();
        criteria.andUserNameEqualTo(userName);
        criteria.andPassowrdEqualTo(passWord);
        criteria.andIsDeleteEqualTo(PublicEnum.NO.name());

        List<User> list = userMapper.selectByExample(userCriteria);
        boolean flag = CollectionUtil.isEmpty(list) ? false : true;
        // 模拟场景:如果用户登录成功,则异步给用户发送邮件通知
        if (flag) {
            final String subject = "登录通知信息";
            final String toMail = "2268549298@qq.com";
            String msg = MessageTemplateUtil.getMessageTemplate(MessageType.EMAIL.getValue());
            msg = msg.replace("{loginDate}", DateUtil.dateToTimeString())
                    .replace("{IP}", loginIp);
            final String content = msg;

            /**
             * 异步提交任务至线程池执行(防止MQ挂掉后,这里一直还在往MQ中发消息,造成等待超时)
             * update:最好是用能获取线程执行的返回结果的方法,不然线程任务中抛出异常了都不知道.难排查错误!
             */
            FutureTask<String> futureTask = new FutureTask<String>(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    sendLoginMail(toMail, subject, content);
                    return "ok";
                }
            });
            executorService.submit(futureTask);
            try {
                futureTask.get();
            } catch (InterruptedException e) {
                e.printStackTrace();
                logger.error("发送邮件异常...");
            } catch (ExecutionException e) {
                logger.error("发送邮件异常...");
                e.printStackTrace();
            }
        }
        return flag;
    }

    /**
     * 将邮件信息发送至消息队列
     *
     * @param toMail
     * @param subject
     * @param content
     */
    private void sendLoginMail(String toMail, String subject, String content) {
        MailParam mailParam = new MailParam(toMail, subject, content);
        notifySendFacade.sendMailNotify(mailParam);
    }
}
