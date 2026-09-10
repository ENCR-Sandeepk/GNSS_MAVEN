#include <gpiod.h>
#include <stdio.h>
#include <unistd.h>

int main() {
    struct gpiod_chip *chip;
    struct gpiod_line *line;
    int ret;

    chip = gpiod_chip_open_by_name("gpiochip3");
    line = gpiod_chip_get_line(chip, 10); // Replace with your GPIO number

    gpiod_line_request_output(line, "beagleplay", 0);
printf("LED Blinking start\n");
while(1)
{
//    printf("GPIO OFF\n");
    gpiod_line_set_value(line, 1);
    sleep(1);

  //  printf("GPIO ON\n");
    gpiod_line_set_value(line, 0);
    sleep(1);
}
printf("LED Blinking stop\n");

    gpiod_line_release(line);
    gpiod_chip_close(chip);
    return 0;
}
