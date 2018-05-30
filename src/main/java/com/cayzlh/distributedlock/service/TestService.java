package com.cayzlh.distributedlock.service;

import com.cayzlh.distributedlock.annotations.DistributeLock;
import org.springframework.stereotype.Service;

/**
 * Description:
 *
 * <p></p>
 *
 * @author Ant丶
 * @date 2018-05-30.
 */
@Service
public class TestService {


    @DistributeLock(name = "test", value = "#key.concat(#value)")
    public String distributedLockTest(String key, String value) throws InterruptedException {

        Thread.sleep(5000L);
        return "distributedLockTest";

    }
}
