#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <unistd.h>

#define MAX_DEVICES 4
#define DEVICE_NAME "flip3-cover-keys"
#define DEVICE_VENDOR 0x1f03
#define DEVICE_PRODUCT 0x0003

static int emit_event(int fd, int type, int code, int value) {
    struct input_event event;
    memset(&event, 0, sizeof(event));
    event.type = type;
    event.code = code;
    event.value = value;
    return write(fd, &event, sizeof(event)) == sizeof(event) ? 0 : -1;
}

static int create_keys(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = DEVICE_VENDOR;
    setup.id.product = DEVICE_PRODUCT;
    strncpy(setup.name, DEVICE_NAME, UINPUT_MAX_NAME_SIZE - 1);
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0
        || ioctl(fd, UI_SET_KEYBIT, KEY_VOLUMEUP) < 0
        || ioctl(fd, UI_SET_KEYBIT, KEY_VOLUMEDOWN) < 0
        || ioctl(fd, UI_DEV_SETUP, &setup) < 0
        || ioctl(fd, UI_DEV_CREATE) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

int main(int argc, char **argv) {
    prctl(PR_SET_PDEATHSIG, SIGTERM);
    int count = argc - 1;
    if (count < 1 || count > MAX_DEVICES) {
        fprintf(stderr, "usage: coverkeys /dev/input/eventN...\n");
        return 2;
    }
    int keys = create_keys();
    if (keys < 0) {
        perror("uinput");
        return 1;
    }
    struct pollfd fds[MAX_DEVICES + 1];
    fds[0].fd = STDIN_FILENO;
    fds[0].events = POLLIN;
    for (int i = 0; i < count; i++) {
        int fd = open(argv[i + 1], O_RDONLY | O_CLOEXEC);
        if (fd < 0 || ioctl(fd, EVIOCGRAB, 1) < 0) {
            perror(argv[i + 1]);
            return 1;
        }
        fds[i + 1].fd = fd;
        fds[i + 1].events = POLLIN;
    }
    printf("ready\n");
    fflush(stdout);
    while (poll(fds, count + 1, -1) >= 0) {
        if (fds[0].revents) {
            char drain[16];
            if (read(STDIN_FILENO, drain, sizeof(drain)) <= 0) {
                break;
            }
        }
        for (int i = 1; i <= count; i++) {
            if (!(fds[i].revents & POLLIN)) {
                continue;
            }
            struct input_event event;
            if (read(fds[i].fd, &event, sizeof(event)) != sizeof(event) || event.type != EV_KEY) {
                continue;
            }
            if (event.code == KEY_POWER) {
                if (event.value == 1) {
                    printf("power\n");
                    fflush(stdout);
                }
            } else if (event.code == KEY_VOLUMEUP || event.code == KEY_VOLUMEDOWN) {
                int swapped = event.code == KEY_VOLUMEUP ? KEY_VOLUMEDOWN : KEY_VOLUMEUP;
                emit_event(keys, EV_KEY, swapped, event.value);
                emit_event(keys, EV_SYN, SYN_REPORT, 0);
                if (event.value == 1) {
                    printf("volume\n");
                    fflush(stdout);
                }
            }
        }
    }
    ioctl(keys, UI_DEV_DESTROY);
    return 0;
}
