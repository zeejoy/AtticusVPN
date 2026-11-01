/*
 * Copyright (c) 2026 TianzeL
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.lan.atticusvpn.server.apple;

import java.util.Base64;

/**
 * 公开测试夹具，不是真实部署密钥。由 JDK 17 keytool 离线新建，只用于单元测试。
 * CA 与 client CN 明确含 TEST ONLY；本文件包含的客户端私钥已公开，严禁用于设备或服务。
 * 有效期 2020-01-01 至 2049 年，client SAN=client.apple.atticus.test，CA 直接签 client。
 * client=RSA-2048、digitalSignature、clientAuth；CA=CA:true、keyCertSign/cRLSign。
 */
final class AppleTestFixtures {
    private AppleTestFixtures() { }
    static char[] password() {
        return "TEST-ONLY-Password-123!".toCharArray();
    }
    static byte[] ca() {
        return Base64.getDecoder().decode(
            "MIIDBjCCAe6gAwIBAgIJAPMiPoH8Wg8KMA0GCSqGSIb3DQEBCwUAMCIxIDAeBgNVBAMTF0F0dGljdXNWUE4gVEVTVCBPTkxZ" +
            "IENBMB4XDTE5MTIzMTE2MDAwMFoXDTQ5MTIzMDE2MDAwMFowIjEgMB4GA1UEAxMXQXR0aWN1c1ZQTiBURVNUIE9OTFkgQ0Ew" +
            "ggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDmuK3M8RFjc3uypGW3yG6TUMW+6Uaj4ZexBBQZVTVlo0tG6FEBwU95" +
            "ZcR4Rz3i55pwYb8QrTXlz4wnhdexuU1e03hlh65tWI2jZ+4gsISrvKnOKjGI0SKpMWLQ0NPL4hc8TciFK0eZQjn4S/AMEpoS" +
            "pVs4d3+L8T214H48wkOd6ycXq6hzN/l++yuBMAb/j+61jzYnjB7em5EpIzdh6Xg5yvAPMKJf/5Ze0h8qjEA0GWF7QaJ5UuW/" +
            "omgbyXBVuajg9nLGLHxVh915IRiky91SozDcrd+4mj0lwbqO02Y2C8VSRVEJR+fXKB/+6sStuYNqnVGq5HWv5RXivlfBNVbL" +
            "AgMBAAGjPzA9MB0GA1UdDgQWBBT83dMU+Qqo7RK7qZbL2dB1tCPfaDALBgNVHQ8EBAMCAQYwDwYDVR0TAQH/BAUwAwEB/zAN" +
            "BgkqhkiG9w0BAQsFAAOCAQEAU1cmj7XBIC7fzedABku0PVXMqKQ30A1mXHNM682Sg8TygjL+ueLVfDq539kY1fRWZcfmaTlW" +
            "cHM4UiwSNUwseC2CIEpU8lplPqXRmY/Sa+2MM7EPX+09qiDWrhn9n6Uol8R7bfEMok1Xn8m2XykF7/MQdWsJQ8tf92PoaVbR" +
            "PCu6zp0vnv1+Fb/1xbPf5bYKaqIXuxa/71q1G4FH0H941M5rNFe0ikv4Z/GVVA2/jBwMQF4UZr1yMBpMAXc1aq9oPuAi+Jfx" +
            "mmtlK6OXuZIfMy1favNeGANlYgBTIhjTdPqPPZZhqMhIiBuYzSNeT1zFipGE0lPCfmoOiBWI4nruuA==");
    }
    static byte[] client() {
        return Base64.getDecoder().decode(
            "MIIOHgIBAzCCDcgGCSqGSIb3DQEHAaCCDbkEgg21MIINsTCCBbgGCSqGSIb3DQEHAaCCBakEggWlMIIFoTCCBZ0GCyqGSIb3" +
            "DQEMCgECoIIFQDCCBTwwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFJnc8QlejFnuyEU071qBv+Ca+bx1AgInEAIB" +
            "IDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQKy+he9DuHgbJB2pQtA/UxgSCBNDV0Tbp5bbak31ZcvP9Cyf64ZwtEqdz" +
            "cOrMvmq8IuI9xkY7GJfq9jQTvzH0GfwbiE3jWBpprKYrZ+66z50OkazfZnLpoDyFvbOWnEa1WdkHcIm5Z3Jfdbho2gD4qlb0" +
            "1aiqxhhiRnpzvMxoA81y1JIKrkir3XV9CZRScYvoHWb8ZzSkgDpE3gQm2vSR/F/O10fo3sI1ojx0PV6+J1/IeqOVjKtcfFFF" +
            "GIY0kjpd3fEgqv+xyWevGMTWdIgXc2E7y/pfDJPqmpfm7+PZYTbAxVbiTQwGfgWYiQc5BL8HxVz4/q72PBqsOU/oz/KAEI2q" +
            "mQ3t9LS06Je2CUVVVim5tIX75nOzp0DSo7wl0KQ9KSBs2iCfgPKHyYuw4baf5fMx97EaU9JgzH7OZ96ApefqeB7fpGySB9mJ" +
            "wpBRlSJiOTxsnzb/ELF9qmzUfYzZx33jIRDrFjek7mR+xFp1tN/emzjSUjpbqx5t4ZNfiXjFdCOhkqXYf34u8wpLM6rntFYE" +
            "IRT853bP7QSavk4vJzVHa9S6u5o8bW+uPqKdYZt4HU4ptYOlSlpz1WagWBdK3WFjjOHjfsOXGhRldm0OSN6sKtdrThPx+TEq" +
            "bRqV53V0dSLeipYBz+nZPZHYCr976DGKpDEGioM0vC9fmzI9K1uz8ATa931BXgnwMGssddh24vo6nodXxU1xn32O6QTrhuQq" +
            "+t/vXGMk0JJYPYTA/lKs3XCIGANr9CZ2vROBcWoi2rFzWhZ9cnw4xjxcA9c4E0QBeJIaQCB/kc6/r0g8OysFfQkwFw0RGesZ" +
            "baaIF7FvCDoivgT5Vylg1l+hNDjHBwY4X0lZNla7F6hL1LpVgcTih7Od+QwoHxtLjP8Rf/dcMfFpepWpcoAORdsZYshz+6IB" +
            "RlSNckxu6g7chn6Z+sxuuYeWU5qcvQ5MEVuBXpTYs9OKJrTIBAn0ScIqfni6KE7O+xcg1FzeJQIY3F0m6oh4HWBt9Nt2Z5Km" +
            "VJXvN3tmiUmLG66tu+TeM8zMtYX1mcnvFOx9l9/bvYNftDjK2G9hakWuuGDZkkOg4VCxqPGLbtniVrfKTxOeQdQkCsjM7xxb" +
            "1J7jlrxIOPWtWZ9AB2HWtyYhtdtjpRNDCAicr8IucZuKdbqZZ0g5dLq6/A5hkuD7irryuL3EdIotRz7rVMfY5yhORBzJCF4v" +
            "xuhzRR0iMqMStmOjtl+aOTgsIG0nnBzswEqeiYToYGPirZlKdATZSLgYzjmERYIpOyu8lSLmhILNsQx0zWrhwAfR1fOXRDZn" +
            "Q/mg1Jof8ROvosCqwlevgpQ1mi4jQrgACtEYVQBsSSu94zwvUnHGkMqYqglUQh/rcngC3geo+JExgkGfT9b+ytZDih1NJB1p" +
            "zSGZ4UddZKpovEpvRAvKi9vw7FF0zTQ08pVV0fGLWD2EAOXVZ9sUUCb3hchth6OZiJ+ZPgmrH/HIGzMq0JHSUXlWJ8AFsAsD" +
            "qIC3KUtsx91ja1uu7xLr8g/daIc/wRBq7MDBTG5aJEedaQotggIaZKgK+rUg+xIm5u9rzF4H2GoshiEryVN4rDE9St28Ygg4" +
            "0mpoPLxAmPZ0tIiji6jnS6ozVJ1ajzRR53lS7ncV302cxmFF7ILS1/6hhmrTLTob3kUHgmqomEZIIzFKMCUGCSqGSIb3DQEJ" +
            "FDEYHhYAdABlAHMAdAAtAGMAbABpAGUAbgB0MCEGCSqGSIb3DQEJFTEUBBJUaW1lIDE3ODk1NjcxODgwNDYwggfxBgkqhkiG" +
            "9w0BBwagggfiMIIH3gIBADCCB9cGCSqGSIb3DQEHATBmBgkqhkiG9w0BBQ0wWTA4BgkqhkiG9w0BBQwwKwQUynQCGmpKkl/g" +
            "xYGFuLkN6Q5BfKoCAicQAgEgMAwGCCqGSIb3DQIJBQAwHQYJYIZIAWUDBAEqBBCJ0/pdFnD3oye39tkBQ3o+gIIHYPs8sg4H" +
            "F+9zXvMoG886EkkX6pCaOu8h3bmz/Du9UyIdH7w/vf7E1wJsNHMoWSYf3YjPBNkJmQ3J6Rvqtrf2N8m3ku6SUFZGm69yFvgb" +
            "DFFm8qQYQb5SmqgBTOKq8IwmchwXzMcZBkNsZoT0KDLqpd/8pAUU84PJRwcsxhBy0tAZJpvbHKkH6wyoAOaQ4pdp0dQUEeUW" +
            "OgbJ/jlIW7HhsIn3Qpka+6SzquBsegt8LNgQGU8yY0iTMwtfPvSt+31jnQ343BBrxaDPDw5ja7bZvrdfu0r3dhEjtfb4EJYT" +
            "GddfOdECADHQU7+q6hlJnmc/X/5o/wkLnVTPEE/VDkH8LN/h6j6KJv1aeW+PWBSe5+R+F9VqqOb4dmJjz6jqKgsDttDnzRRm" +
            "HTy9Thm8e6psl7tYlFKZXB/k3lcucDo8R49B+0fwPjMzMZycDIm49+ur1x6T9eA1eLDSNDlciTRbiP3ouMgclJK01kede2bu" +
            "1Z+yxr2B1aOcKLQA7ERlFSalbRuKI0iOyHseoztw/Y4nmAUtIWYU33Fwqdd0FPHgSAcBjwP/tgZXeE+WHjbDTwj3Hi7tqYzd" +
            "w8MlTIRlGOQW21E8dE+CZfvyLXIAtSaScihG7+URXkDnbQshTu+viYKd2MvKg+nyKDtpRnpXRKFp9tZUJ0IW7FbPiFPWnkSh" +
            "RGrdY3VPgq230sZpf4d5Z4+vtlES4uIkSFN5N4DFO+G7x9PPWzaNJKBObwtMOnVFCDZGosa8Sy/3j26y8gnwIHaTrMIXqb2+" +
            "FhXw1L+tbhMm80AIAhP77tq5Z6o6wt3CLAC9YGSRxWwnWB/jJqaVgA+maFjAuK2lJXnhD2RUSfnzHYZOs9bHfMjqjiKHjM4Y" +
            "9n8zl/2z6bjoUSbE+a+1KYbESyfSVJeFEEX/co47Km50LCbLNa6G1GxcicMDB2a1kGcx2eAniTYH9loLpDPu8gXlw+nRF1SI" +
            "cBRHX/asVb7gcclmqOJXQXIlqJ5rf4jNiudtclajgsSEV5h5Cj5X93DHHOX1yZ6KRNCnEX9kRFWEzbA90TMHFkjgrw1dduTZ" +
            "SnncwI6CIFR7MbnHwblXg+UjdShFc9t1hG5Q6aCpcbBAgtAUSSerzqZyoC3frzwF9C8A1yk7tunW24sljhLw2NfTqGfa737s" +
            "ALNtnmaVzlWT883GVNk/2V4+GOB1VguubEAH+p00nh0XqlrLAZ9IHUx+9hpwNo4g7Gc7HUiRlEbasEeARiSKT1BMaPQ8N+4G" +
            "/4jsasN3wU+CtASqeELCJJWTkblEVKilz6SK2oMkZJPZGnTI0EEcjTp+cP0uNmXaFvCByCWNmWBrWjjzK4U3L6vHugssQ0GF" +
            "2vDh7ChITA35scy6rgOGCKH1JNVMQTogLiXwpVcscWqPnUiKLfVJ42ag6ASg1pSXVDcSSThrmvEzv0qCTeHFNpKMOSgYrKtI" +
            "u0yRCIsg1QNRdzoQJktT8yINtsVnps9pyh0nX7UYYJPKPdgrgiEcso2+pFf7WSfojYseAW6xurerhrIkWxkHagY4KVW5ljc5" +
            "Czb2oU3+K+aGFBnizyIBqKUvaZDOqyBDZme8CjsSx7CPh63bCPLsfsfGYiDNfdCl6dpMlJTgeqW75DIo6IteLAw39gcfARCJ" +
            "bweaYIQMGm1wNS6aJpsYvSY55hqYrmS9I34wUac28PWr48aj+pFvCImSvkaRbKiOuIhsAxMy70wz+F8fqHHQv0XrvbzMjpO3" +
            "agM5ij4qqg8QnjWFmJ0WiZqA9V+33WhX2UpoiZV/tVqed4PiDDYr2H8yHC3oLTlsFRF0ziTwXtgNz5ZwzFOVJW1Qdxl9hXas" +
            "uxoZZAVILNO4LhB1Yy1GhVdwW73lpe5c2hgoKEtx/KgVivjSf3xm4ckAk2la/fZApH9nxp0lwswuD1JxLXgl4j8lMib1vlFA" +
            "PMTDa3VU4s//TcyTPvCimXWNyu4aTvvjy54zmO8Yqn0oOotNWTR5jYjYJQfddf442NTEnS68vJNa3ijKVHyo1TptGzcpAGXN" +
            "3ZagsRKlUOXaMD9MzV5OGZ9zdX3xhpDI2w+6DcumagDoDChujVVnZmHcj4Yw9LMijLGO9VS7cDwAjoB+IxwJlzzpJ/wAzheM" +
            "PbsGQ40Rd0ravrj4btJH+hgp50rMlfNOHDGn/XEnTuRv45ZEj+kPIoadcIOmifXnrEA1Bo16pTWkM5VG+lmBWZ7xAvxZmP5P" +
            "pLUkTjsNl0w+VTantKqRuGZEOehT1Ta3XvfiXjebBtQtCCVL3//KQe/RZEy9e82WK0ucbniykjEAGDDsAt2LKahu/de1xtE4" +
            "J9GCEFA9MSrPYhQwcy7W762Jjci83ohNJc4xgYyeas5rhdksie8V5jmb69/eNZE8PfTOcE1eEH/IGxEMpYY+m4yJqQncmYcE" +
            "iFwfPnKdD5+JB1BM3sYsDN/K9c/QqGoTBMbsOl4D7iT951uiuZa2yXGkH8d37dvoqu55VD/JdUEtg9mYcSsrTWizqFQRGKFm" +
            "32NrRIu7SYZNoQgwTTAxMA0GCWCGSAFlAwQCAQUABCCG10aARIk7JcdeOXwqqWJ3m/QxDqPbvyaFBVD7HQy6ewQUVX7sFf9I" +
            "ZZ1hL/30o4azvpnsorcCAicQ");
    }
}

