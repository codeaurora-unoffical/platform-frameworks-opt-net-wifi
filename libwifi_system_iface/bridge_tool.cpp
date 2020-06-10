/*
 * Copyright (c) 2020, The Linux Foundation. All rights reserved.

 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *  * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above
 *    copyright notice, this list of conditions and the following
 *    disclaimer in the documentation and/or other materials provided
 *    with the distribution.
 *  * Neither the name of The Linux Foundation, Inc. nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.

 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#include "wifi_system/bridge_tool.h"

#include <net/if.h>
#include <net/if_arp.h>
#include <netinet/in.h>
#include <sys/socket.h>

#include <linux/if_bridge.h>

#include <vector>
#include <string.h>

#include <android-base/logging.h>
#include <android-base/unique_fd.h>

namespace android {
namespace wifi_system {
namespace {

#define MAX_PORTS 1024
#define IFNAMSIZ    16

}

bool BridgeTool::createBridge(const std::string& br_name) {
    base::unique_fd sock(socket(PF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0));

    if (TEMP_FAILURE_RETRY(ioctl(sock, SIOCBRADDBR, br_name.c_str())) != 0) {
        LOG(ERROR) << "Could not add bridge " << br_name.c_str()
                   << " (" << strerror(errno) << ")";
        return false;
    }

    return true;
}

bool BridgeTool::deleteBridge(const std::string& br_name) {
    base::unique_fd sock(socket(PF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0));

    if (TEMP_FAILURE_RETRY(ioctl(sock, SIOCBRDELBR, br_name.c_str())) != 0) {
        LOG(ERROR) << "Could not remove bridge " << br_name.c_str()
                   << " (" << strerror(errno) << ")";
        return false;
    }
    return true;
}

bool BridgeTool::addIfaceToBridge(const std::string& br_name, const std::string& if_name) {
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));

    ifr.ifr_ifindex = if_nametoindex(if_name.c_str());
    if (ifr.ifr_ifindex == 0) {
        LOG(ERROR) << "Interface is not exist: " << if_name.c_str();
        return false;
    }
    strlcpy(ifr.ifr_name, br_name.c_str(), IFNAMSIZ);

    base::unique_fd sock(socket(PF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0));
    if (TEMP_FAILURE_RETRY(ioctl(sock, SIOCBRADDIF, &ifr)) != 0) {
        LOG(ERROR) << "Could not add interface " << if_name.c_str()
                   << " into bridge " << ifr.ifr_name
                   << " (" << strerror(errno) << ")";
        return false;
    }
    return true;
}

bool BridgeTool::removeIfaceFromBridge(const std::string& br_name, const std::string& if_name) {
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));

    ifr.ifr_ifindex = if_nametoindex(if_name.c_str());
    if (ifr.ifr_ifindex == 0) {
        LOG(ERROR) << "Interface is not exist: " << if_name.c_str();
        return false;
    }
    strlcpy(ifr.ifr_name, br_name.c_str(), IFNAMSIZ);

    base::unique_fd sock(socket(PF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0));
    if (TEMP_FAILURE_RETRY(ioctl(sock, SIOCBRDELIF, &ifr)) != 0) {
        LOG(ERROR) << "Could not remove interface " << if_name.c_str()
                   << " from bridge " << ifr.ifr_name
                   << " (" << strerror(errno) << ")";
        return false;
    }

    return true;
}


bool BridgeTool::GetBridges(std::vector<std::string>* bridges) {
  base::unique_fd sock(socket(PF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0));
  if (sock.get() < 0) {
    LOG(ERROR) << "Failed to open socket to get bridge interfaces ("
               << strerror(errno) << ")";
    return false;
  }

  int num, i, ifindices[MAX_PORTS];
  char br_name[IFNAMSIZ];
  unsigned long args[3];

  memset(ifindices, 0, MAX_PORTS);

  args[0] = BRCTL_GET_BRIDGES;
  args[1] = (unsigned long)ifindices;
  args[2] = MAX_PORTS;
  num = ioctl(sock.get(), SIOCGIFBR, args);

  for (i = 0; i < num; i++) {
    memset(br_name, 0, IFNAMSIZ);
    if (ifindices[i] ==0 || !if_indextoname(ifindices[i], br_name)) {
      continue;
    }
    bridges->push_back(br_name);
  }
  return true;
}


bool BridgeTool::GetInterfacesInBridge(std::string br_name,
                           std::vector<std::string>* interfaces) {
   base::unique_fd sock(socket(PF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0));
   if (sock.get() < 0) {
       LOG(ERROR) << "Failed to open socket to get bridge interfaces ("
                  << strerror(errno) << ")";
       return false;
   }

   struct ifreq request;
   int i, ifindices[MAX_PORTS];
   char if_name[IFNAMSIZ];
   unsigned long args[3];

   memset(ifindices, 0, MAX_PORTS);

   args[0] = BRCTL_GET_PORT_LIST;
   args[1] = (unsigned long) ifindices;
   args[2] = MAX_PORTS;

   strlcpy(request.ifr_name, br_name.c_str(), IFNAMSIZ);
   request.ifr_data = (char *)args;

   if (ioctl(sock.get(), SIOCDEVPRIVATE, &request) < 0) {
       LOG(ERROR) << "Failed to ioctl SIOCDEVPRIVATE to get interfaces in bridge";
       return false;
   }

   for (i = 0; i < MAX_PORTS; i++ ) {
       memset(if_name, 0, IFNAMSIZ);
       if (ifindices[i] == 0 || !if_indextoname(ifindices[i], if_name)) {
           continue;
       }
       interfaces->push_back(if_name);
   }
   return true;
}

} // namespace wifi_system
} // namespace android
