package com.cayzlh.distributedlock.web;

import com.cayzlh.distributedlock.service.TestService;
import org.springframework.beans.factory.annotation.Autowired;

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
