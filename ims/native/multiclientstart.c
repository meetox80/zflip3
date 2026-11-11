#include <grp.h>
#include <stdio.h>
#include <unistd.h>

#define DAEMON_PATH "/system/bin/multiclientd"
#define AID_RADIO 1001
#define AID_LOG 1007
#define AID_SDCARD_RW 1015
#define AID_CACHE 2001
#define AID_INET 3003
#define AID_READPROC 3009
#define AID_MISC 9998

int main(void) {
    gid_t groups[] = {AID_RADIO, AID_CACHE, AID_INET, AID_MISC, AID_LOG, AID_READPROC, AID_SDCARD_RW};
    if (setgroups(7, groups) < 0 || setgid(AID_RADIO) < 0 || setuid(AID_RADIO) < 0) {
        perror("identity");
        return 1;
    }
    execl(DAEMON_PATH, DAEMON_PATH, "-s", "2", (char *) NULL);
    perror("exec");
    return 1;
}
