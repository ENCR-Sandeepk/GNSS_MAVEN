#include <gpiod.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>


#define ENABLE 			1
#define DISABLE 		0
#define HIGH 			1
#define LOW				0
#define ON 				1
#define OFF				0
#define I2C_SDA		    23
#define I2C_SCK         22
#define INT				9
#define PWM				11
#define AN              10
#define RST				12
#define CS				13
#define SCK 			14
#define CIPO 			7
#define CIPI			8

struct gpiod_chip *chip1;
struct gpiod_line *gnss_pw_en;
int main()
{
 chip1 = gpiod_chip_open_by_name("gpiochip3");
 gnss_pw_en 		 =	 gpiod_chip_get_line(chip1, PWM);
 gpiod_line_request_output(gnss_pw_en, "beagleplay", OFF);


gpiod_line_set_value(gnss_pw_en, DISABLE);

sleep(3);
gpiod_line_set_value(gnss_pw_en, ENABLE); 

  gpiod_line_release (gnss_pw_en);
  gpiod_chip_close(chip1);

}
