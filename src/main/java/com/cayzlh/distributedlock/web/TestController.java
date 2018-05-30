package com.cayzlh.distributedlock.web;

import com.cayzlh.distributedlock.service.TestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Description:
 *
 * <p></p>
 *
 * @author Ant丶
 * @date 2018-05-30.
 */
@RestController
@RequestMapping(value = "/test")
public class TestController {

    @Autowired
    private TestService testService;

    @RequestMapping(value = "/distributedLockTest/{key}/{value}", method = {RequestMethod.POST, RequestMethod.GET})
    public String distributedLockTest(@PathVariable String key, @PathVariable String value) throws InterruptedException {
        return testService.distributedLockTest(key, value);
    }

}
