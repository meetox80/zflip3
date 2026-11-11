#include <fcntl.h>
#include <grp.h>
#include <linux/capability.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <sys/xattr.h>
#include <unistd.h>

#define SOCKET_PATH "/dev/socket/imsd"
#define SOCKET_LABEL "u:object_r:magisk_file:s0"
#define DAEMON_PATH "/system/bin/imsd"
#define AID_SYSTEM 1000
#define AID_RADIO 1001
#define AID_INET 3003
#define AID_NET_RAW 3004
#define AID_NET_ADMIN 3005

int main(void) {
    unlink(SOCKET_PATH);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        perror("socket");
        return 1;
    }
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    strncpy(address.sun_path, SOCKET_PATH, sizeof(address.sun_path) - 1);
    if (bind(fd, (struct sockaddr *) &address, sizeof(address)) < 0) {
        perror("bind");
        return 1;
    }
    chmod(SOCKET_PATH, 0666);
    if (setxattr(SOCKET_PATH, "security.selinux", SOCKET_LABEL, strlen(SOCKET_LABEL) + 1, 0) < 0) {
        perror("setxattr");
        return 1;
    }
    char value[16];
    snprintf(value, sizeof(value), "%d", fd);
    setenv("ANDROID_SOCKET_imsd", value, 1);
    gid_t groups[] = {AID_SYSTEM, AID_RADIO, AID_NET_RAW, AID_INET, AID_NET_ADMIN};
    if (setgroups(5, groups) < 0 || setgid(AID_SYSTEM) < 0) {
        perror("groups");
        return 1;
    }
    prctl(PR_SET_KEEPCAPS, 1, 0, 0, 0);
    if (setuid(AID_SYSTEM) < 0) {
        perror("setuid");
        return 1;
    }
    struct __user_cap_header_struct header = {_LINUX_CAPABILITY_VERSION_3, 0};
    struct __user_cap_data_struct data[2];
    memset(data, 0, sizeof(data));
    unsigned int wanted = (1u << CAP_NET_RAW) | (1u << CAP_NET_ADMIN);
    data[0].effective = wanted;
    data[0].permitted = wanted;
    data[0].inheritable = wanted;
    if (syscall(SYS_capset, &header, data) < 0) {
        perror("capset");
        return 1;
    }
    prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_RAISE, CAP_NET_RAW, 0, 0);
    prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_RAISE, CAP_NET_ADMIN, 0, 0);
    execl(DAEMON_PATH, DAEMON_PATH, (char *) NULL);
    perror("exec");
    return 1;
}
